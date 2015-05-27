# carvoyant-splunk-modular-input
This is a Java based modular input to connect your Carvoyant account to your Splunk server.

## Prerequisites
Java 1.7+
Maven2

## Installation
1. Clone from git
2. Run a Maven package (ie, "mvn package")
3. Unzip the target/CarvoyantModularInput-0.0.1-SNAPSHOT.zip package into you $SPLUNK_HOME/etc/apps directory
4. Restart your Splunk server

## Create a Data Input
Go into your Data Input configuration and add a new input of type "Carvoyant Modular Input". You will need the following information:

* Client Id : This is the client id from your Carvoyant developer account.
* Client Secret : This is the secret for your client id.
* Access Token : This is the OAuth2 access token granting your client id access to your Carvoyant account.
* Refresh Token : This is the OAuth2 refresh token allowing Splunk to keep an active access token to your account
* Expiration Date : This is the date that your access token expires (number of milliseconds from the Unix epoch). Set to 0 at creation and the MI will manage this for you.
* Interval (under more settings) : Set this to the polling intervale that you want data to be updated. If you leave this blank, the input will only run once. It's highly suggested that you make this 60 seconds or longer. Any less and you run the risk of a large query not completing before the next iteration.

Currently, there are no publicly available tools to allow you to easily create your access token and refresh tokens. You can code through the [Carvoyant OAuth2 process](http://docs.carvoyant.com/en/latest/getting-started/oauth2-delegated-access.html) or email support@carvoyant.com your client id and account id (or Carvoyant username) and we'll generate one for you. This will be made easier in future releases.

## What to Expect
The first time the input runs, no data will be pulled. This will set the initial query point within the Carvoyant system. If you need to pull historical data into your Splunk system, that will need to be done outside of this MI. Contact us at support@carvoyant.com if you are looking for options to do so.

After the first initialization call, any new data elements on any vehicle in your account will be added to splunk. The specific types of data will depend upon what data your individual vehicles collect.