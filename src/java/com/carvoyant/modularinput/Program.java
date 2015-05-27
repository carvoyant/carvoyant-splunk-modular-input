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
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.stream.XMLStreamException;

import org.json.JSONArray;
import org.json.JSONObject;

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
	public Scheme getScheme() {

		Scheme scheme = new Scheme("Carvoyant Modular Input");
		scheme.setDescription("Generates events containing Carvoyant vehicle information. Events will be generated from future Carvoyant data. Data in the Carvoyant system from prior to the creation of this input will not be transferred.");

		Argument clientId = new Argument("clientId", "Client Id");
		clientId.setDescription("The Carvoyant Client Id for your Carvoyant developer account.");
		clientId.setDataType(Argument.DataType.STRING);
		clientId.setRequiredOnCreate(true);
		clientId.setRequiredOnEdit(true);
		scheme.addArgument(clientId);

		Argument clientSecret = new Argument("clientSecret", "Client Secret");
		clientSecret.setDescription("The secret for the above Client Id.");
		clientSecret.setDataType(Argument.DataType.STRING);
		clientSecret.setRequiredOnCreate(true);
		clientSecret.setRequiredOnEdit(true);
		scheme.addArgument(clientSecret);

		Argument tokenArgument = new Argument("token", "Access Token");
		tokenArgument.setDescription("The Carvoyant Access Token for your account (generated with the same Client Id specified above).");
		tokenArgument.setDataType(Argument.DataType.STRING);
		tokenArgument.setRequiredOnCreate(true);
		tokenArgument.setRequiredOnEdit(true);
		scheme.addArgument(tokenArgument);

		Argument refreshTokenArgument = new Argument("refreshToken", "Refresh Token");
		refreshTokenArgument.setDescription("The Carvoyant Refresh Token for the above Access Token.");
		refreshTokenArgument.setDataType(Argument.DataType.STRING);
		refreshTokenArgument.setRequiredOnCreate(true);
		refreshTokenArgument.setRequiredOnEdit(true);
		scheme.addArgument(refreshTokenArgument);

		Argument expirationDate = new Argument("expirationDate", "Expiration Date (milliseconds from epoch)");
		expirationDate.setDescription("The expiration date of the Carvoyant Access Token. The modular input will manage this value.");
		expirationDate.setRequiredOnCreate(true);
		expirationDate.setRequiredOnEdit(true);
		scheme.addArgument(expirationDate);

		return scheme;
	}

	@Override
	public void streamEvents(InputDefinition inputs, EventWriter ew) throws MalformedDataException, XMLStreamException, IOException {

		for (String inputName : inputs.getInputs().keySet()) {

			String clientId = ((SingleValueParameter) inputs.getInputs().get(inputName).get("clientId")).getValue();
			String clientSecret = ((SingleValueParameter) inputs.getInputs().get(inputName).get("clientSecret")).getValue();
			String token = ((SingleValueParameter) inputs.getInputs().get(inputName).get("token")).getValue();
			String refreshToken = ((SingleValueParameter) inputs.getInputs().get(inputName).get("refreshToken")).getValue();

			SingleValueParameter expDate = (SingleValueParameter) inputs.getInputs().get(inputName).get("expirationDate");
			long expirationDate = 0;

			if (expDate != null) {
				expirationDate = expDate.getLong();
			}

			long expirationWindow = TimeUnit.DAYS.toMillis(1);
			long currentTime = System.currentTimeMillis();

			if ((expirationDate - currentTime) < expirationWindow) {
				BufferedReader in = null;

				try {
					// Refresh token
					URL url = new URL("https://api.carvoyant.com/oauth/token");
					HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
					urlConnection.setReadTimeout(10000);
					urlConnection.setConnectTimeout(15000);
					urlConnection.setRequestMethod("POST");
					urlConnection.setDoInput(true);
					urlConnection.setDoOutput(true);

					List<SimpleEntry> params = new ArrayList<SimpleEntry>();
					params.add(new SimpleEntry("client_id", clientId));
					params.add(new SimpleEntry("client_secret", clientSecret));
					params.add(new SimpleEntry("grant_type", "refresh_token"));
					params.add(new SimpleEntry("refresh_token", refreshToken));

					String userpass = clientId + ":" + clientSecret;
					String basicAuth = "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(userpass.getBytes());

					urlConnection.setRequestProperty("Authorization", basicAuth);

					OutputStream os = urlConnection.getOutputStream();
					BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
					writer.write(getQuery(params));
					writer.flush();
					writer.close();
					os.close();

					urlConnection.connect();

					if (urlConnection.getResponseCode() < 400) {
						in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
						String inputLine;
						while ((inputLine = in.readLine()) != null) {
							JSONObject obj = new JSONObject(inputLine);
							token = obj.getString("access_token");
							refreshToken = obj.getString("refresh_token");
							int ttl = obj.getInt("expires_in");
							expirationDate = System.currentTimeMillis() + (long) ttl * 1000;

							// Set Splunk variables through their API
							String[] inputNameArray = inputName.split("://");
							URL urlupdate = new URL(inputs.getServerUri() + "/servicesNS/nobody/launcher/data/inputs/" + URLEncoder.encode(inputNameArray[0], "UTF-8") + "/"
									+ URLEncoder.encode(inputNameArray[1], "UTF-8"));
							HttpURLConnection urlConnectionUpdate = (HttpURLConnection) urlupdate.openConnection();
							urlConnectionUpdate.setReadTimeout(10000);
							urlConnectionUpdate.setConnectTimeout(15000);
							urlConnectionUpdate.setRequestMethod("POST");
							urlConnectionUpdate.setDoInput(true);
							urlConnectionUpdate.setDoOutput(true);

							List<SimpleEntry> paramsUpdate = new ArrayList<SimpleEntry>();
							paramsUpdate.add(new SimpleEntry("clientId", clientId));
							paramsUpdate.add(new SimpleEntry("clientSecret", clientSecret));
							paramsUpdate.add(new SimpleEntry("token", token));
							paramsUpdate.add(new SimpleEntry("refreshToken", refreshToken));
							paramsUpdate.add(new SimpleEntry("expirationDate", expirationDate));

							urlConnectionUpdate.setRequestProperty("Authorization", "Splunk " + inputs.getSessionKey());

							OutputStream osUpdate = urlConnectionUpdate.getOutputStream();
							BufferedWriter writerUpdate = new BufferedWriter(new OutputStreamWriter(osUpdate, "UTF-8"));
							writerUpdate.write(getQuery(paramsUpdate));
							writerUpdate.flush();
							writerUpdate.close();
							osUpdate.close();

							urlConnectionUpdate.connect();
							BufferedReader inUpdate = null;
							if (urlConnectionUpdate.getResponseCode() < 400) {
								inUpdate = new BufferedReader(new InputStreamReader(urlConnectionUpdate.getInputStream()));
								String inputLineUpdate;
								while ((inputLineUpdate = inUpdate.readLine()) != null) {
									ew.synchronizedLog(EventWriter.INFO, inputLineUpdate);
								}
							} else {
								inUpdate = new BufferedReader(new InputStreamReader(urlConnectionUpdate.getErrorStream()));
								String inputLineUpdate;
								while ((inputLineUpdate = inUpdate.readLine()) != null) {
									ew.synchronizedLog(EventWriter.ERROR, inputName + " Splunk Update Error: " + inputLineUpdate);
								}
							}
						}
					} else {
						in = new BufferedReader(new InputStreamReader(urlConnection.getErrorStream()));
						String inputLine;
						while ((inputLine = in.readLine()) != null) {
							ew.synchronizedLog(EventWriter.ERROR, inputName + " Carvoyant Refresh Token Error: " + inputLine);
						}
					}

					in.close();
					urlConnection.disconnect();

				} catch (IOException e) {
					ew.synchronizedLog(EventWriter.INFO, inputName + " tokenException: " + e.toString());
				}

			}

			// Get Carvoyant Data
			try {
				URL url = new URL("https://api.carvoyant.com/v1/api/account/data/?sinceLastCall=true");
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
				ew.synchronizedLog(EventWriter.ERROR, inputName + " error: " + ioe.toString());
			} catch (ParseException pe) {
				ew.synchronizedLog(EventWriter.ERROR, inputName + " error: " + pe.toString());
			} catch (MalformedDataException e) {
				ew.synchronizedLog(EventWriter.ERROR, "MalformedDataException in writing event to input" + inputName + ": " + e.toString());
			}
		}
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
