import akka.util.Duration
import annotation.tailrec
import blueeyes.bkka.AkkaDefaults._
import akka.dispatch.{Future, ExecutionContext, Await}
import blueeyes.bkka.FutureMonad
import blueeyes.core.data._
import blueeyes.core.http.HttpResponse
import blueeyes.core.service.engines.HttpClientXLightWeb
import blueeyes.json._
import blueeyes.core.service._
import java.nio._
import org.joda.time.format.DateTimeFormat
import scala.Left
import scala.Right
import scala.Some
import scalaz.StreamT
import org.joda.time.DateTime
import scalaz.syntax.monad._
import blueeyes.core.data.DefaultBijections._

object TweetAnalysisIngestion {


  implicit val httpClient=new HttpClientXLightWeb()(defaultFutureDispatch)
  implicit val as=actorSystem
  val ec=implicitly[ExecutionContext]
  implicit val M = new FutureMonad(ec)

  def parseInt(s : String) : Option[Int] = try {
      Some(s.toInt)
    } catch {
      case _ : java.lang.NumberFormatException => None
    }

  def main(args:Array[String]){

    if (args.length != 6) {
      println("Wrong number of parameters.")
      println("Usage: TweetAnalysisIngestion search_term precog_host precog_ingest_path precog_apiKey alchemy_apiKey minutes_between_requests")
      actorSystem.shutdown()
      sys.exit(1)
    } else {

      val pageSize=30

      val target=args(0)
      val host=args(1)
      val ingestPath=args(2)
      val apiKey=args(3)
      val alchemyApiKey=args(4)

      val intervals=parseInt(args(5)).get

      val fullPath = "%s/ingest/v1/sync/fs%s".format(host, ingestPath)

      @tailrec
      def analyzeRec(time:Int, sinceId:Option[String]){
        val maxId=Await.result(analyzeSentiments(target, pageSize, alchemyApiKey, apiKey, fullPath,sinceId), Duration("10 minutes"))
        val nextMaxId=if (maxId==None) sinceId else maxId // or maxId.map(Some(_)).getOrElse(sinceId)
        Thread.sleep(time)
        analyzeRec(time,nextMaxId)
      }

      println("Processing, press Ctrl+C to interrupt")
      analyzeRec(intervals*60*1000,None)

      /*
      intervals.map(t => analyzeRec(t*60*1000,None)).getOrElse(
        Await.result(analyzeSentiments(target, pageSize, alchemyApiKey, apiKey, fullPath,None),Duration("10 minutes"))
      )*/

      actorSystem.shutdown()
    }
  }

  def analyzeSentiments(target: String, pageSize: Int, alchemyApiKey: String, apiKey: String, fullPath: String, sinceId:Option[String])(implicit httpClient:HttpClientXLightWeb):Future[Option[String]] ={
    println("Polling")
    val tweetSearchStream: StreamT[Future, JValue] = buildStream(searchTwitter(target)(pageSize)(sinceId) _)

    val textAnalyzedTweets: StreamT[Future, List[JObject]] = tweetSearchStream.flatMap(jv => {
      val jarr=results(jv)
      val tweets = jarr.elements
      val sentimentOf=getTextSentiment(alchemyApiKey)_
      val categoryOf=getTextCategory(alchemyApiKey)_
      val conceptOf=getTextRankedConcepts(alchemyApiKey)_
      val fdocSentiments:Future[List[JValue]] = Future.sequence(tweets.map(tweet => {
        val text= (tweet \ "text" --> classOf[JString]).value
        val sentiment:Future[Option[JValue]]=sentimentOf(text, target).map( _.flatMap( _ \? "docSentiment").flatMap(scoreAsNum(_)))
        val category:Future[Option[JValue]]= categoryOf(text).map( _.flatMap( jv=>{
          val extractor=extractField(jv)(x => x)_
          val jobj=JObject(extractor("category")::extractor("score")::Nil)
          fieldAsNum("score")(jobj)
        }))
        val concept:Future[Option[JValue]]= conceptOf(text).map( _.flatMap(_ \? "concepts" ).map( (jv:JValue)=>JArray((jv --> classOf[JArray]).elements.map({ (jo:JValue)=>
          val extractor=extractField(jo)(x => x)_
          val jobj=JObject(extractor("text")::extractor("relevance")::Nil)
          fieldAsNum("relevance")(jobj).getOrElse(JUndefined)
        } ) ) ) )
        val analysis:Future[JValue]=M.lift3((a:Option[JValue],b:Option[JValue],c:Option[JValue])=>
          ((sent:Option[JValue],cat:Option[JValue],conc:Option[JValue])=>
            JObject(JField("sentiment",sent.getOrElse(JUndefined))::JField("category",cat.getOrElse(JUndefined))::JField("concept",conc.getOrElse(JUndefined))::Nil))(a,b,c))(sentiment,category,concept)
        analysis
      }))
      val fTweetsSentiments = fdocSentiments.map(analysis => tweets.zip(analysis).map(ts => {
        val (tweet, txtAnalysis) = ts
        pickTweetData(tweet, txtAnalysis)
      }))
      fTweetsSentiments
    }.liftM[StreamT])
    textAnalyzedTweets.isEmpty.foreach( isEmpty =>{
      if (!isEmpty){
        val body:ByteChunk=Right(textAnalyzedTweets.map(xs => {
          val asString=xs.foldLeft(new StringBuilder)((sb,jv)=>sb.append("%s\n".format(jv.renderCompact))).toString()
          val bytes=ByteBuffer.wrap(asString.getBytes("UTF-8"))
          println(new String(bytes.array))
          bytes
        }))
        println("Sending to Precog")
        val fresponse=httpClient.parameters('apiKey -> apiKey).post(fullPath)(body)
        fresponse.map( response =>response match {
          case HttpResponse(_, _, Some(Left(buffer)), _) => println(new String(buffer.array(), "UTF-8"))
          case _ => println("Unexpected response %s".format(response))
        })
      }
    })
    //TODO get the max with a fold in tweetSearchStream, headOption reevaluates the head
    tweetSearchStream.headOption.map(_.flatMap( jv=> (jv \?"max_id_str").map(j=> (j--> classOf[JString]).value)))
  }

  def extractField[T](jv:JValue)(f:JValue=>JValue)(field:String)=JField(field,f(jv \ field))

  def pickTweetData(tweet:JValue, analysis:JValue)={
    val tweetField=extractField(tweet)_
    val tweetIdField= (name:String)=>tweetField(x => x)(name)
    JObject(
      tweetIdField("id")::
      tweetIdField("from_user")::
      tweetIdField("text")::
      tweetIdField("geo")::
      JField("analysis",analysis)::
      tweetField(jv=>JString(DateTime.parse( (jv-->classOf[JString]).value, DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z")).toString))("created_at")::
      Nil
    )
  }

  def scoreAsNum(jv:JValue) = fieldAsNum("score")(jv)
  def fieldAsNum(field:String)(jval:JValue) = (jval \? field).map(fieldVal=> jval.set(field,JNum(BigDecimal((fieldVal --> classOf[JString]).value))))

  def searchTwitter(target:String)(pageSize:Int)(sinceId:Option[String])(page:Int)(implicit httpClient:HttpClientXLightWeb):Future[Option[JValue]] ={
    val baseParams=List('q -> target,'rpp->"%s".format(pageSize), 'page->"%s".format(page))
    val allParams= sinceId.map( sId=> ('since_id->sId)::baseParams).getOrElse(baseParams)
    httpClient.parameters(allParams:_*).get[JValue]("http://search.twitter.com/search.json").map(_.content)
  }

  def buildStream(f:Int=> Future[Option[JValue]])=
    StreamT.unfoldM(1)( page => f(page).map( _.flatMap(jv => if (results(jv).elements.isEmpty) None else Some((jv,page+1)) )))

  def results(jv:JValue)=jv \ "results" -->classOf[JArray]

  def getTextSentiment(alchemyApiKey:String)(text:String, target:String)(implicit httpClient:HttpClientXLightWeb):Future[Option[JValue]]=
    callAlchemyText("TextGetTargetedSentiment",alchemyApiKey, List('text->text,'target-> target))

  def getTextCategory(alchemyApiKey:String)(text:String)(implicit httpClient:HttpClientXLightWeb):Future[Option[JValue]]=
    callAlchemyText("TextGetCategory",alchemyApiKey, List('text->text))

  def getTextRankedConcepts(alchemyApiKey:String)(text:String)(implicit httpClient:HttpClientXLightWeb):Future[Option[JValue]]=
    callAlchemyText("TextGetRankedConcepts",alchemyApiKey, List('text->text))

  def callAlchemyText(apiMethod:String, alchemyApiKey:String, params:Seq[(Symbol,String)])(implicit httpClient:HttpClientXLightWeb):Future[Option[JValue]]={
    val parameters=List('apikey -> alchemyApiKey, 'outputMode->"json")++params
    httpClient.parameters(parameters:_*)
      .get[String]("http://access.alchemyapi.com/calls/text/%s".format(apiMethod))
      .map(x=> jsonFromResponseContent(x))
  }

  def jsonFromResponseContent(r:HttpResponse[String]): Option[JValue] = {
    r.content.map(cleanUp(_)).flatMap(c => JParser.parseFromString(c).toOption)
  }

  def cleanUp(s:String)=s.replaceAll("\n|\r","")

}