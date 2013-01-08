Tweet Text Analysis Ingestion
=============================

This utility does text analysis (by Alchemy) from twitter data and ingest it into precog.

It runs a twitter search for the specified term, it process the results through Alchemy API to extract sentiment, relevance, and concepts, and send the results to precog through an ingest call.
After a specified time, it repeats the search from the last tweet analyzed.

Usage:
------

From sbt:
run search_term precog_host precog_ingest_path precog_apiKey alchemy_apiKey minutes_between_requests

* *search_term*: 
    term to search in twitter
    
* *precog_host*: 
    ingestion host

* *precog_ingest_path*:
    path where to store the data including the user id (it must be owned by the api key, i.e. using another api key + user & password is not supported yet

* *precog_apiKey*: 
    api key for ingestion (must have permissions in the ingest path)
    
* *alchemy_apiKey*: 
    Alchemy API key

* *minutes_between_requests*: 
    minutes between twitter api calls

