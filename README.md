# carvoyant-splunk-modular-input
This is a Java based modular input to connect your Carvoyant account to your Splunk server.

## Prerequisites
* Java 1.7+
* Maven2
* A Carvoyant developer account (register [here](https://developer.carvoyant.com))

## Installation
1. Clone from git
2. Run a Maven package (ie, "mvn package")
3. Unpack the target/CarvoyantModularInput-{release}.spl package into your $SPLUNK_HOME/etc/apps directory
4. Restart your Splunk server

If you don't want to build from source, you can use one of the pre-build tarballs from the [releases](https://github.com/carvoyant/carvoyant-splunk-modular-input/tree/master/releases) directory on GitHub.

## Create a Data Input
Go into your Data Input configuration and add a new input of type "Carvoyant Modular Input". You will need the following information:

* Collection Interval : Set this to the polling interval that you want data to be updated. If you leave this blank, the input will only run once. It's highly suggested that you make this 60 seconds or longer. Any less and you run the risk of a large query not completing before the next iteration.
* Client Id : This is the client id from your Carvoyant developer account.
* Client Secret : This is the secret for your client id.
* Access Token : This is the OAuth2 access token granting your client id access to your Carvoyant account.
* Refresh Token : This is the OAuth2 refresh token allowing Splunk to keep an active access token to your account
* Expiration Date : This is the date that your access token expires (number of milliseconds from the Unix epoch). Set to 0 at creation and the MI will manage this for you.

Note that after entering in your Client Id and Client Secret, you can select the Retrieve button to authenticate your Carvoyant user account and populate the access token, refresh token, and expiration date fields automatically.

## What to Expect
The first time the input runs, no data will be pulled. This will set the initial query point within the Carvoyant system. If you need to pull historical data into your Splunk system, that will need to be done outside of this MI. Contact us at support@carvoyant.com if you are looking for options to do so.

After the first initialization call, any new data elements on any vehicle in your account will be added to splunk. The specific types of data will depend upon what data your individual vehicles collect.