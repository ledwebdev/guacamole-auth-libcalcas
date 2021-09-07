/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.auth.cas;

import com.google.inject.Inject;
import com.google.inject.Provider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Scanner;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.ServletException;

import org.apache.http.HttpResponse;
import org.apache.http.HttpHeaders;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.NameValuePair;

import org.apache.guacamole.form.Field;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.auth.credentials.CredentialsInfo;
import org.apache.guacamole.net.auth.credentials.GuacamoleInvalidCredentialsException;
import org.apache.guacamole.auth.cas.conf.ConfigurationService;
import org.apache.guacamole.auth.cas.form.CASTicketField;
import org.apache.guacamole.auth.cas.ticket.TicketValidationService;
import org.apache.guacamole.auth.cas.user.CASAuthenticatedUser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Service providing convenience functions for the CAS AuthenticationProvider
 * implementation. Adding LibCal API support.
 */
public class AuthenticationProviderService {

    /**
     * Service for retrieving CAS configuration information.
     */
    @Inject
    private ConfigurationService confService;

    /**
     * Service for validating received ID tickets.
     */
    @Inject
    private TicketValidationService ticketService;

    /**
     * Provider for AuthenticatedUser objects.
     */
    @Inject
    private Provider<CASAuthenticatedUser> authenticatedUserProvider;

    /**
     * Definition for minute reconciliation.
     */
    private long ONE_MINUTE_IN_MILLIS=60000;//millisecs

    /**
     * Returns date for today in LibCal format.
     *
     */
    public String sortOutDate() {
        String libcalDatePattern = "yyyy-MM-dd";

        DateTimeFormatter libcalDateFormatter = DateTimeFormatter.ofPattern(libcalDatePattern);
        String today = libcalDateFormatter.format(LocalDateTime.now());

        return today;
    }//sortOutDate

    /**
     * Returns boolean value for date-time range.
     *
     * @param fromDate
     *     The booked date.
     *
     * @param end
     *     The calculated date for end of session.
     *
     * @return
     *     Boolean value for whether in range.
     */
    public static boolean isNowBetweenDateTime(Date start, Date end) {
         final Date now = new Date();
         return now.after(start) && now.before(end);
    }//isNowBetweenDateTime

    /**
     * Returns boolean value for date-time range.
     *
     * @param fromDate
     *     The booked date.
     *
     * @param minutes
     *     The number of minutes to use for calculation.
     *
     * @return
     *     Boolean value for whether in range.
     */
    public boolean withinRange(String fromDate, int minutes) {
        DateTimeFormatter timeFormatter = DateTimeFormatter.ISO_DATE_TIME;
        OffsetDateTime offsetDateTime = OffsetDateTime.parse(fromDate, timeFormatter);

        Date date = Date.from(Instant.from(offsetDateTime));
        long t= date.getTime();
        Date afterAddingSession=new Date(t + (minutes * ONE_MINUTE_IN_MILLIS));
        if (isNowBetweenDateTime(date,afterAddingSession)) return true;
        return false;
    }//withinRange


    /**
     * Returns an AuthenticatedUser representing the user authenticated by the
     * given credentials.
     *
     * @param credentials
     *     The credentials to use for authentication.
     *
     * @return
     *     A CASAuthenticatedUser representing the user authenticated by the
     *     given credentials.
     *
     * @throws GuacamoleException
     *     If an error occurs while authenticating the user, or if access is
     *     denied.
     */
    public CASAuthenticatedUser authenticateUser(Credentials credentials)
            throws GuacamoleException {

        // Pull CAS ticket from request if present
        System.out.println("CAS request");
        HttpServletRequest request = credentials.getRequest();
        String username = null;
        String mail = null;
        String ticket = null;
        String fromDate = null;
        int station = 0;

        if (request != null) {
            System.out.println("checking ticket...");
            ticket = request.getParameter(CASTicketField.PARAMETER_NAME);
            //not used but this is syntax for passing parameters
            /*
            machine = request.getParameter("machine");
            System.out.println("MACHINE: " + machine);
            */

            if (ticket != null) {
                System.out.println("ticket not null...");
                Map<String, String> tokens = ticketService.validateTicket(ticket, credentials);
                System.out.println("using entrySet and toString");
                for (Entry<String, String> entry : tokens.entrySet()) {
                    if (entry.getKey().contains("CAS_MAIL")) {
                        mail = entry.getValue().toLowerCase();
                        if (mail.contains("@"))
                            mail = mail.split("@")[0];
                    }//if
                }//for
                username = credentials.getUsername();
                
                if (username != null) {
                    station = -1;

                    //Get calendar bookings
                    System.out.println("got username, now checking bookings...");
                    try {
                        List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
                        CloseableHttpClient client = HttpClients.createDefault();
                        HttpPost post = new HttpPost(confService.getOauthServer() + "/oauth/token");
                        nameValuePairs.add(new BasicNameValuePair("client_id",confService.getClientId()));
                        nameValuePairs.add(new BasicNameValuePair("client_secret",confService.getClientSecret()));
                        nameValuePairs.add(new BasicNameValuePair("grant_type", "client_credentials"));
                        post.setEntity(new UrlEncodedFormEntity(nameValuePairs));
                        HttpResponse response = client.execute(post);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

                        String line = reader.readLine();
                        JSONObject obj = new JSONObject(line);
                        HttpGet get = new HttpGet(confService.getOauthServer() + "/space/bookings?lid=" +
                             confService.getCalendarId() + "&date=" + sortOutDate()); 
                        get.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + obj.getString("access_token"));
                        response = client.execute(get);
                        reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
                        line = reader.readLine();
                        line = "{\"bookings\":" + line + "}";
                        obj = new JSONObject(line);
                        System.out.println("looping through bookings...");
                        JSONArray bookings = obj.getJSONArray("bookings");
                        for (int i = 0; i < bookings.length(); i++) {
                            JSONObject bObj = bookings.getJSONObject(i);
                            String email = bObj.getString("email");
                            if (email.startsWith(username + "@") || email.startsWith(mail + "@")) { 
                                fromDate = bObj.getString("fromDate");
                                station = bObj.getInt("eid");
                            }//if
                        }//for
                        if (fromDate != null && station != -1) {
                            System.out.println("found booking, checking times for " + fromDate + "...");
                            if (!withinRange(fromDate,confService.getSessionMins())) {
                                System.out.println("timing is wrong...");
                                username = null;
                                station = -1;
                            }//if
                        }//if
                    } catch (IOException ioe) {
                        System.out.println("ioe prob: " + ioe.toString());
                        username = null;
                    }//try

                    if (username != null && station != -1) {
                        System.out.println("Redirect to CAS for authentication...");
                        CASAuthenticatedUser authenticatedUser = authenticatedUserProvider.get();
                        username = "ADC Virtual";
                        credentials.setUsername(Integer.toString(station));
                        credentials.setPassword(Integer.toString(station));
                        authenticatedUser.init(username, credentials, tokens);
                        return authenticatedUser;
                    }//if
                }//if username
            }//if ticket
        }//if request
                    
        //Request CAS ticket service 
        if (username == null && station == 0) {
            System.out.println("off to CAS service...");
            throw new GuacamoleInvalidCredentialsException("Invalid login.",
                new CredentialsInfo(Arrays.asList(new Field[] {
                    // CAS-specific ticket (will automatically redirect the user
                    // to the authorization page via JavaScript)
                    new CASTicketField(
                        confService.getAuthorizationEndpoint(),
                        confService.getRedirectURI()
                    )
                 }))
            );
        }//if

        //If this point is reached, credentials are fine but the booking is a problem.
        System.out.println("Redirect to message about no bookings...");
        throw new GuacamoleInvalidCredentialsException("Invalid login.",
            new CredentialsInfo(Arrays.asList(new Field[] {
                new CASTicketField(
                    confService.getLibCalRedirectURI(),
                    confService.getLibCalRedirectURI()
                )
            }))
        );

    }//authenticateUser

}//AuthenticationProviderService
