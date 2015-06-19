/*
 * Copyright 2015 Carvoyant, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"): you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.carvoyant.modularinput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.stream.XMLStreamException;

import org.json.JSONArray;
import org.json.JSONObject;

import com.splunk.Input;
import com.splunk.InputCollection;
import com.splunk.SSLSecurityProtocol;
import com.splunk.Service;
import com.splunk.ServiceArgs;
import com.splunk.modularinput.Argument;
import com.splunk.modularinput.Event;
import com.splunk.modularinput.EventWriter;
import com.splunk.modularinput.InputDefinition;
import com.splunk.modularinput.MalformedDataException;
import com.splunk.modularinput.Scheme;
import com.splunk.modularinput.Script;
import com.splunk.modularinput.SingleValueParameter;

public class Program extends Script {

	public static void main(String[] args) {
		new Program().run(args);
	}

	@Override
    protected String stackTraceToLogEntry(Exception e) {
        // Concatenate all the lines of the exception's stack trace with \\ between them.
        StringBuilder sb = new StringBuilder();
        
        sb.append("\"");
        sb.append(e.getClass().getName());
        
        if (e.getMessage() != null) {
	        sb.append(": ");
	        sb.append(e.getMessage());
        }
        
        sb.append("\" at ");
        for (StackTraceElement s : e.getStackTrace()) {
            sb.append(s.toString());
            sb.append("\\\\");
        }
        return sb.toString();
    }

	@Override
	public Scheme getScheme() {

		Scheme scheme = new Scheme("Carvoyant Modular Input");
		scheme.setUseExternalValidation(false);
		scheme.setDescription("Generates events containing Carvoyant vehicle information. Events will be generated from future Carvoyant data. Data in the Carvoyant system from prior to the creation of this input will not be transferred.");

		Argument name = new Argument("name");
		name.setDescription("name");
		name.setDataType(Argument.DataType.STRING);
		name.setRequiredOnCreate(true);
		name.setRequiredOnEdit(false);
		scheme.addArgument(name);

		Argument clientId = new Argument("clientId");
		clientId.setDescription("The Carvoyant Client Id for your Carvoyant developer account.");
		clientId.setDataType(Argument.DataType.STRING);
		clientId.setRequiredOnCreate(true);
		clientId.setRequiredOnEdit(true);
		scheme.addArgument(clientId);

		Argument clientSecret = new Argument("clientSecret");
		clientSecret.setDescription("The secret for the above Client Id.");
		clientSecret.setDataType(Argument.DataType.STRING);
		clientSecret.setRequiredOnCreate(true);
		clientSecret.setRequiredOnEdit(true);
		scheme.addArgument(clientSecret);

		Argument tokenArgument = new Argument("token");
		tokenArgument.setDescription("The Carvoyant Access Token for your account (generated with the same Client Id specified above).");
		tokenArgument.setDataType(Argument.DataType.STRING);
		tokenArgument.setRequiredOnCreate(true);
		tokenArgument.setRequiredOnEdit(true);
		scheme.addArgument(tokenArgument);

		Argument refreshTokenArgument = new Argument("refreshToken");
		refreshTokenArgument.setDescription("The Carvoyant Refresh Token for the above Access Token.");
		refreshTokenArgument.setDataType(Argument.DataType.STRING);
		refreshTokenArgument.setRequiredOnCreate(true);
		refreshTokenArgument.setRequiredOnEdit(true);
		scheme.addArgument(refreshTokenArgument);

		Argument expirationDate = new Argument("expirationDate");
		expirationDate.setDescription("The expiration date of the Carvoyant Access Token. The modular input will manage this value.");
		expirationDate.setDataType(Argument.DataType.STRING);
		expirationDate.setRequiredOnCreate(false);
		expirationDate.setRequiredOnEdit(false);
		scheme.addArgument(expirationDate);

		return scheme;
	}

	@Override
	public void streamEvents(InputDefinition inputs, EventWriter ew) throws MalformedDataException, XMLStreamException, IOException {

		Service splunkSvc = getService(inputs.getServerUri(), inputs.getSessionKey());
		
		for (String inputName : inputs.getInputs().keySet()) {
			String clientId = ((SingleValueParameter) inputs.getInputs().get(inputName).get("clientId")).getValue();
			String clientSecret = ((SingleValueParameter) inputs.getInputs().get(inputName).get("clientSecret")).getValue();
			String token = ((SingleValueParameter) inputs.getInputs().get(inputName).get("token")).getValue();
			String refreshToken = ((SingleValueParameter) inputs.getInputs().get(inputName).get("refreshToken")).getValue();

			SingleValueParameter expDate = (SingleValueParameter) inputs.getInputs().get(inputName).get("expirationDate");
			long expirationDate = expDate.getLong();
			long expirationWindow = TimeUnit.DAYS.toMillis(1);
			long currentTime = System.currentTimeMillis();

			// If the access token is going to expire, then refresh it
			if ((expirationDate - currentTime) < expirationWindow) {
				JSONObject tokenJson = getRefreshToken(ew, clientId, clientSecret, refreshToken);

				if (tokenJson != null) {
					token = tokenJson.getString("access_token");
					refreshToken = tokenJson.getString("refresh_token");
					int ttl = tokenJson.getInt("expires_in");
					expirationDate = System.currentTimeMillis() + (long) ttl * 1000;

					// Update this configuration with the new settings
					String[] splunkApiInputName = inputName.split("://");
					updateInput(ew, splunkSvc, splunkApiInputName[1], clientId, clientSecret, token, refreshToken, expirationDate);
				} else {
					throw new XMLStreamException("Cannot not refresh the token for " + inputName);
				}
			}

			// Get Carvoyant Data
			try {
				URL url = new URL("https://api.carvoyant.com/v1/api/account/data/?sinceLastCall=true");
//				URL url = new URL("https://sandbox-api.carvoyant.com/sandbox/api/account/data/?sinceLastCall=true");
				URLConnection urlConnection = url.openConnection();

				String basicAuth = "Bearer " + token;
				urlConnection.setRequestProperty("Authorization", basicAuth);

				BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
				String inputLine;
				while ((inputLine = in.readLine()) != null) {

					JSONObject obj = new JSONObject(inputLine);
					JSONArray arr = obj.getJSONArray("data");

					for (int i = 0; i < arr.length(); i++) {
						String timeString = arr.getJSONObject(i).getString("timestamp");
						DateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmssZ");
						Date date = format.parse(timeString);

						Event event = new Event();
						event.setStanza(inputName);
						event.setTime(date);
						event.setData(arr.getJSONObject(i).toString());
						ew.writeEvent(event);
					}
				}

				in.close();
			} catch (IOException ioe) {
				throw new XMLStreamException("Could not retrieve Carvoyant Data", ioe);
			} catch (ParseException pe) {
				throw new XMLStreamException("Could not parse Carvoyant Data", pe);
			}
		}
	}

	private Service getService(String serverUri, String sessionKey) {
		Service service;
		ServiceArgs sa = new ServiceArgs();
		
		String[] serverUriTokens = serverUri.split("://");
		String uriScheme = serverUriTokens[0];
		serverUriTokens = serverUriTokens[1].split(":");
		String uriHost = serverUriTokens[0];
		String uriPort = serverUriTokens[1];
		
		sa.setScheme(uriScheme);
		sa.setHost(uriHost);
		sa.setPort(Integer.parseInt(uriPort));
		sa.setToken("Splunk " + sessionKey);
		try {
	        Service.setSslSecurityProtocol(SSLSecurityProtocol.TLSv1_2);
			service = Service.connect(sa);
	
			// When authenticating using an existing session key, the Service object
			// does not initialize properly, so manually set the version.
			service.version = service.getInfo().getVersion();
		} catch (RuntimeException re) {
	        Service.setSslSecurityProtocol(SSLSecurityProtocol.TLSv1);
			service = Service.connect(sa);
	
			// When authenticating using an existing session key, the Service object
			// does not initialize properly, so manually set the version.
			service.version = service.getInfo().getVersion();
		}

		return service;
	}
	
	// Updates the specified carvoyantModularInput through using the Splunk SDK
	private void updateInput(EventWriter ew, Service service, String inputName, String clientId, String clientSecret, String token, String refreshToken, long expirationDate) {
		InputCollection inputs = service.getInputs();
		
		ew.synchronizedLog(EventWriter.INFO, "Found " + inputs.size() + " inputs on " + service.getScheme() + "://" + service.getHost() + ":" + service.getPort());
		
		boolean updatedMI = false;
		for (Input input : inputs.values()) {
			if (input.getKind().toString().equals("carvoyantModularInput") && input.getName().equals(inputName)) {
				ew.synchronizedLog(EventWriter.INFO, "Updating carvoyantModularInput://" + input.getName() + " with new token.");
				HashMap<String, Object> params = new HashMap<String, Object>();
				params.put("clientId", clientId);
				params.put("clientSecret", clientSecret);
				params.put("token", token);
				params.put("refreshToken", refreshToken);
				params.put("expirationDate", expirationDate);

				input.update(params);
				updatedMI = true;
			}
		}
		
		if (!updatedMI) {
			ew.synchronizedLog(EventWriter.INFO, "Could not update carvoyantModularInput://" + inputName + " with new token.");
		} else {
			ew.synchronizedLog(EventWriter.INFO, "Updated carvoyantModularInput://" + inputName + " with new token.");
		}
	}

	private JSONObject getRefreshToken(EventWriter ew, String clientId, String clientSecret, String refreshToken) {
		JSONObject tokenJson = null;
		HttpsURLConnection getTokenConnection = null;

		try {
			BufferedReader tokenResponseReader = null;
			URL url = new URL("https://api.carvoyant.com/oauth/token");
//			URL url = new URL("https://sandbox-api.carvoyant.com/sandbox/oauth/token");
			getTokenConnection = (HttpsURLConnection) url.openConnection();
			getTokenConnection.setReadTimeout(30000);
			getTokenConnection.setConnectTimeout(30000);
			getTokenConnection.setRequestMethod("POST");
			getTokenConnection.setDoInput(true);
			getTokenConnection.setDoOutput(true);

			List<SimpleEntry> getTokenParams = new ArrayList<SimpleEntry>();
			getTokenParams.add(new SimpleEntry("client_id", clientId));
			getTokenParams.add(new SimpleEntry("client_secret", clientSecret));
			getTokenParams.add(new SimpleEntry("grant_type", "refresh_token"));
			getTokenParams.add(new SimpleEntry("refresh_token", refreshToken));

			String userpass = clientId + ":" + clientSecret;
			String basicAuth = "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(userpass.getBytes());

			getTokenConnection.setRequestProperty("Authorization", basicAuth);

			OutputStream os = getTokenConnection.getOutputStream();
			os.write(getQuery(getTokenParams).getBytes("UTF-8"));
			os.close();

			if (getTokenConnection.getResponseCode() < 400) {
				tokenResponseReader = new BufferedReader(new InputStreamReader(getTokenConnection.getInputStream()));
				String inputLine;
				StringBuffer sb = new StringBuffer();
				while ((inputLine = tokenResponseReader.readLine()) != null) {
					sb.append(inputLine);
				}

				tokenJson = new JSONObject(sb.toString());
				ew.synchronizedLog(EventWriter.INFO, "Refreshed Carvoyant access token.");
			} else {
				tokenResponseReader = new BufferedReader(new InputStreamReader(getTokenConnection.getErrorStream()));
				StringBuffer sb = new StringBuffer();
				String inputLine;
				while ((inputLine = tokenResponseReader.readLine()) != null) {
					sb.append(inputLine);
				}
				ew.synchronizedLog(EventWriter.ERROR, "Carvoyant Refresh Token Error. CLIENT_ID:" + clientId + ", REFRESH_TOKEN:" + refreshToken + ",ERROR_MSG: " + sb.toString());
			}

			getTokenConnection.disconnect();
		} catch (MalformedURLException mue) {
			ew.synchronizedLog(EventWriter.ERROR, "Carvoyant Refresh Token Error: " + mue.getMessage());
		} catch (IOException ioe) {
			ew.synchronizedLog(EventWriter.ERROR, "Carvoyant Refresh Token Error: " + ioe.getMessage());
		} finally {
			if (null != getTokenConnection) {
				getTokenConnection.disconnect();
			}
		}

		return tokenJson;
	}

	private String getQuery(List<SimpleEntry> params) throws UnsupportedEncodingException {
		StringBuilder result = new StringBuilder();
		boolean first = true;

		for (SimpleEntry pair : params) {
			if (first) {
				first = false;
			} else {
				result.append("&");
			}

			result.append(URLEncoder.encode(pair.getKey().toString(), "UTF-8"));
			result.append("=");
			result.append(URLEncoder.encode(pair.getValue().toString(), "UTF-8"));
		}

		return result.toString();
	}
}
