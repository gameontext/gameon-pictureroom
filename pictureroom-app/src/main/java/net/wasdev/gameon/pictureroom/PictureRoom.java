/*******************************************************************************
 * Copyright (c) 2016 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.gameon.pictureroom;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import net.wasdev.gameon.protocol.EventBuilder;
import net.wasdev.gameon.security.SecurityUtils;
import net.wasdev.gameon.security.TheNotVerySensibleHostnameVerifier;
import net.wasdev.gameon.security.TheVeryTrustingTrustManager;

/**
 * A very simple room.
 *
 * The intent of this file is to keep an entire room implementation within one Java file,
 * and to try to minimise its reliance on outside technologies, beyond those required by
 * gameon (WebSockets, Json)
 *
 * Although it would be trivial to refactor out into multiple classes, doing so can make it
 * harder to see 'everything' needed for a room in one go.
 */
@ServerEndpoint("/pictureRoom")
@WebListener
public class PictureRoom implements ServletContextListener {

    private final static String USERNAME = "username";
    private final static String USERID = "userId";
    private final static String BOOKMARK = "bookmark";
    private final static String CONTENT = "content";
    private final static String TYPE = "type";
    private final static String EXIT = "exit";
    private final static String EXIT_ID = "exitId";

    private Set<String> playersInRoom = Collections.synchronizedSet(new HashSet<String>());

    private static final String name = "PictureRoom";
    private static final String fullName = "A gallery of pictures";
    private static final String description = "There are a number of pictures on the wall, in fact it looks like a Rogue's Gallery - or could this be the GameOn! team ... ?";
    
    // for running against the real remote gameon.
    //String registrationUrl = "https://game-on.org/map/v1/sites";
    //String endPointUrl = "ws://<ip and port of host that gameon can reach>/rooms/simpleRoom

    // for when running in a docker container with game-on all running
    // locally.
    private final String registrationUrl = "http://map:9080/map/v1/sites";
    
    private final String endPointUrl;
    
    // credentials, obtained from the gameon instance to connect to.
    private final String userId = "dummy.DevUser";
    private final String key = "a3XQpqGZTsqzn6YK5W7j/Z+4h+K/00A6lLeSoLUwiN8=";
    

    List<String> directions = Arrays.asList( "n", "s", "e", "w", "u", "d");

    private static long bookmark = 0;

    private final Set<Session> sessions = new CopyOnWriteArraySet<Session>();
    private final Map<String, String> exits = new HashMap<>();
    private final List<String> objects = new ArrayList<>();

    public PictureRoom() {
        String url = System.getenv("HOSTNAME");
        try {
            url = "ws://" + InetAddress.getByName(System.getenv("HOSTNAME")).getHostAddress() +":9080/rooms/pictureRoom";
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        endPointUrl = url;
        System.out.println("Websocket endpoint " + endPointUrl);
        exits.put("n", "A Large doorway to the north");
        exits.put("s", "A winding path leading off to the south");
        exits.put("e", "An overgrown road, covered in brambles");
        exits.put("w", "A shiny metal door, with a bright red handle");
        exits.put("u", "A spiral set of stairs, leading upward into the ceiling");
        exits.put("d", "A tunnel, leading down into the earth");
        objects.add("Masterpiece");
        objects.add("Scribble");
        objects.add("Sketch");
        objects.add("Mugshot");
        objects.add("Portrait");
    }
    
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Room registration
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////



    /**
     * Entry point at application start, we use this to test for & perform room registration.
     */
    @Override
    public final void contextInitialized(final ServletContextEvent e) {

        // check if we are already registered..
        try {
            configureSSL();

            HttpURLConnection con = isAlreadyRegistered();
            if (con.getResponseCode() == HttpURLConnection.HTTP_OK) {
                //if the result was 200, then we found a room with this id & owner..
                //which is either a previous registration by us, or another room with
                //the same owner & roomname
                //We won't register our room in this case, although we _could_ choose
                //do do an update instead.. (we'd need to parse the json response, and
                //collect the room id, then do a PUT request with our new data.. )
                System.out.println("We are already registered, so updating with a PUT");
                String json = getJSONResponse(con);
                JsonArray array = Json.createReader(new StringReader(json)).readArray();
                JsonString id = array.getJsonObject(0).getJsonString("_id");
                register("PUT", registrationUrl + "/" + id.getString());
            } else {
                register("POST", registrationUrl);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }
    
    private HttpURLConnection isAlreadyRegistered() throws Exception {
     // build the query request.
        String queryParams = "name=" + name + "&owner=" + userId;
        
        // build the complete query url..
        System.out.println("Querying room registration using url " + registrationUrl);

        URL u = new URL(registrationUrl + "?" + queryParams );
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        if(registrationUrl.startsWith("https://")) {
            ((HttpsURLConnection)con).setHostnameVerifier(new TheNotVerySensibleHostnameVerifier());
        }
        con.setDoOutput(true);
        con.setDoInput(true);
        con.setRequestProperty("Content-Type", "application/json;");
        con.setRequestProperty("Accept", "application/json,text/plain");
        con.setRequestProperty("Method", "GET");
        return con;
    }
    
    private void configureSSL() {
        TrustManager[] trustManager = new TrustManager[] {new TheVeryTrustingTrustManager()};

        // We don't want to worry about importing the game-on cert into
        // the jvm trust store.. so instead, we'll create an ssl config
        // that no longer cares.
        // This is handy for testing, but for production you'd probably
        // want to goto the effort of setting up a truststore correctly.
        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustManager, new java.security.SecureRandom());
        } catch (NoSuchAlgorithmException ex) {
            System.out.println("Error, unable to get algo SSL");
        }catch (KeyManagementException ex) {
            System.out.println("Key management exception!! ");
        }

        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
    }
    
    private void register(String method, String registrationUrl) throws Exception {
        System.out.println("Beginning registration.");
        String registrationPayloadString = getRegistration();

        Instant now = Instant.now();
        String dateValue = now.toString();

        String bodyHash = SecurityUtils.buildHash(registrationPayloadString);

        System.out.println("Building hmac with "+userId+dateValue+bodyHash);
        String hmac = SecurityUtils.buildHmac(Arrays.asList(new String[] {
                                   userId,
                                   dateValue,
                                   bodyHash
                               }),key);


        // build the complete registration url..
        System.out.println("Beginning registration using url " + registrationUrl);
        URL u = new URL(registrationUrl);
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        if(registrationUrl.startsWith("https://")) {
            ((HttpsURLConnection)con).setHostnameVerifier(new TheNotVerySensibleHostnameVerifier());
        }
        con.setDoOutput(true);
        con.setDoInput(true);
        con.setRequestProperty("Content-Type", "application/json;");
        con.setRequestProperty("Accept", "application/json,text/plain");
        con.setRequestProperty("Method", method);
        con.setRequestProperty("gameon-id", userId);
        con.setRequestProperty("gameon-date", dateValue);
        con.setRequestProperty("gameon-sig-body", bodyHash);
        con.setRequestProperty("gameon-signature", hmac);
        con.setRequestMethod(method);
        OutputStream os = con.getOutputStream();

        os.write(registrationPayloadString.getBytes("UTF-8"));
        os.close();

        System.out.println("RegistrationPayload :\n "+registrationPayloadString);

        int httpResult = con.getResponseCode();
        if (httpResult == HttpURLConnection.HTTP_OK || httpResult == HttpURLConnection.HTTP_CREATED) {
            System.out.println("Registration reports success.");
            getJSONResponse(con);
            // here we should remember the exits we're told about,
            // so we can
            // use them when the user does /go direction
            // But we're not dealing with exits here (yet)..
            // user's will have to /sos out of us .. (bad, but ok
            // for now)
        } else {
            System.out.println(
                    "Registration gave http code: " + con.getResponseCode() + " " + con.getResponseMessage());
            // registration sends payload with info why registration
            // failed.
            try (BufferedReader buffer = new BufferedReader(
                    new InputStreamReader(con.getErrorStream(), "UTF-8"))) {
                String response = buffer.lines().collect(Collectors.joining("\n"));
                System.out.println(response);
            }
            System.out.println("Room Registration FAILED .. this room has NOT been registered");
        }
    }
    
    private String getJSONResponse(HttpURLConnection con) throws Exception {
        try (BufferedReader buffer = new BufferedReader(
                new InputStreamReader(con.getInputStream(), "UTF-8"))) {
            String response = buffer.lines().collect(Collectors.joining("\n"));
            System.out.println("Response from server.");
            System.out.println(response);
            return response;
        }
    }
    
    //build the registration JSON for this room
    private String getRegistration() {
        System.out.println("Websocket endpoint " + endPointUrl);

        JsonObjectBuilder registrationPayload = Json.createObjectBuilder();
        // add the basic room info.
        registrationPayload.add("name", name);
        registrationPayload.add("fullName", fullName);
        registrationPayload.add("description", description);
        // add the doorway descriptions we'd like the game to use if it
        // wires us to other rooms.
        JsonObjectBuilder doors = Json.createObjectBuilder();
        for(Entry<String, String> exit : exits.entrySet()) {
            doors.add(exit.getKey(), exit.getValue());
        }
        registrationPayload.add("doors", doors.build());

        // add the connection info for the room to connect back to us..
        JsonObjectBuilder connInfo = Json.createObjectBuilder();
        connInfo.add("type", "websocket"); // the only current supported
                                           // type.
        connInfo.add("target", endPointUrl);
        registrationPayload.add("connectionDetails", connInfo.build());

        return registrationPayload.build().toString();
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // Here we could deregister, if we wanted.. we'd need to read the registration/query
        // response to cache the room id, so we could remove it as we shut down.
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Websocket methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @OnOpen
    public void onOpen(Session session, EndpointConfig ec) {
        System.out.println("A new connection has been made to the room.");

        //send ack
        sendRemoteTextMessage(session, "ack,{\"version\":[1]}");
    }

    @OnClose
    public void onClose(Session session, CloseReason r) {
        System.out.println("A connection to the room has been closed");
    }

    @OnError
    public void onError(Session session, Throwable t) {
        if(session!=null){
            sessions.remove(session);
        }
        System.out.println("Websocket connection has broken");
        t.printStackTrace();
    }

    @OnMessage
    public void receiveMessage(String message, Session session) throws IOException {
        String[] contents = splitRouting(message);

        // Who doesn't love switch on strings in Java 8?
        switch(contents[0]) {
            case "roomHello":
                sessions.add(session);
                addNewPlayer(session, contents[2]);
                break;
            case "room":
                processCommand(session, contents[2]);
                break;
            case "roomGoodbye":
                removePlayer(session, contents[2]);
                break;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Room methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // add a new player to the room
    private void addNewPlayer(Session session, String json) throws IOException {
        if (session.getUserProperties().get(USERNAME) != null) {
            return; // already seen this user before on this socket
        }
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = getValue(msg.get(USERNAME));
        String userid = getValue(msg.get(USERID));

        if (playersInRoom.add(userid)) {
            // broadcast that the user has entered the room
            EventBuilder.playerEvent(Collections.singletonList(session),
                    userid, "You have entered the room", "Player " + username + " has entered the room");
           
            EventBuilder.locationEvent(Collections.singletonList(session),
                    userid, name, fullName, description, exits, objects, Collections.emptyList(), Collections.emptyMap());
        }
    }

    // remove a player from the room.
    private void removePlayer(Session session, String json) throws IOException {
        sessions.remove(session);
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = getValue(msg.get(USERNAME));
        String userid = getValue(msg.get(USERID));
        playersInRoom.remove(userid);

        // broadcast that the user has left the room
        sendMessageToRoom(session, "Player " + username + " has left the room", null, userid);
    }

    // process a command
    private void processCommand(Session session, String json) throws IOException {
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String userid = getValue(msg.get(USERID));
        String username = getValue(msg.get(USERNAME));
        String content = getValue(msg.get(CONTENT)).toString();
        String lowerContent = content.toLowerCase();

        System.out.println("Command received from the user, " + content);

        // handle look command
        if (lowerContent.equals("/look")) {
            // resend the room description when we receive /look
            EventBuilder.locationEvent(Collections.singletonList(session),
                    userid, name, fullName, description, exits, objects, Collections.emptyList(), Collections.emptyMap());
            return;
        }
        
        String examine = "/examine ";
        if(lowerContent.startsWith(examine)) {
            String item = lowerContent.substring(examine.length());
            PictureRoomPicture pic = PictureRoomPicture.getInstance(item, item);
            EventBuilder.playerEvent(Collections.singletonList(session), userId, pic.getDescription(), null);
            return;
        }

        if (lowerContent.startsWith("/go")) {

            String exitDirection = null;
            if (lowerContent.length() > 4) {
                exitDirection = lowerContent.substring(4).toLowerCase();
            }

            if ( exitDirection == null || !directions.contains(exitDirection) ) {
                sendMessageToRoom(session, null, "Hmm. That direction didn't make sense. Try again?", userid);
            } else {
                // Trying to go somewhere, eh?
                JsonObjectBuilder response = Json.createObjectBuilder();
                response.add(TYPE, EXIT)
                .add(EXIT_ID, exitDirection)
                .add(BOOKMARK, bookmark++)
                .add(CONTENT, "Run Away!");

                sendRemoteTextMessage(session, "playerLocation," + userid + "," + response.build().toString());
            }
            return;
        }

        // reject all unknown commands
        if (lowerContent.startsWith("/")) {
            sendMessageToRoom(session, null, "Unrecognised command - sorry :-(", userid);
            return;
        }

        // everything else is just chat.
        EventBuilder.chatEvent(session, username, content);
        return;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Reply methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void sendMessageToRoom(Session session, String messageForRoom, String messageForUser, String userid)
            throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add(TYPE, "event");

        JsonObjectBuilder content = Json.createObjectBuilder();
        if (messageForRoom != null) {
            content.add("*", messageForRoom);
        }
        if (messageForUser != null) {
            content.add(userid, messageForUser);
        }

        response.add(CONTENT, content.build());
        response.add(BOOKMARK, bookmark++);

        if(messageForRoom==null){
            sendRemoteTextMessage(session, "player," + userid + "," + response.build().toString());
        }else{
            broadcast(sessions, "player,*," + response.build().toString());
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Util fns.
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private String[] splitRouting(String message) {
        ArrayList<String> list = new ArrayList<>();

        int brace = message.indexOf('{');
        int i = 0;
        int j = message.indexOf(',');
        while (j > 0 && j < brace) {
            list.add(message.substring(i, j));
            i = j + 1;
            j = message.indexOf(',', i);
        }
        list.add(message.substring(i));

        return list.toArray(new String[] {});
    }

    private static String getValue(JsonValue value) {
        if (value.getValueType().equals(ValueType.STRING)) {
            JsonString s = (JsonString) value;
            return s.getString();
        } else {
            return value.toString();
        }
    }

    /**
     * Simple text based broadcast.
     *
     * @param session
     *            Target session (used to find all related sessions)
     * @param message
     *            Message to send
     * @see #sendRemoteTextMessage(Session, RoutedMessage)
     */
    public void broadcast(Set<Session> sessions, String message) {
        for (Session s : sessions) {
            sendRemoteTextMessage(s, message);
        }
    }

    /**
     * Try sending the {@link RoutedMessage} using
     * {@link Session#getBasicRemote()}, {@link Basic#sendObject(Object)}.
     *
     * @param session
     *            Session to send the message on
     * @param message
     *            Message to send
     * @return true if send was successful, or false if it failed
     */
    public boolean sendRemoteTextMessage(Session session, String message) {
        if (session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
                return true;
            } catch (IOException ioe) {
                // An IOException, on the other hand, suggests the connection is
                // in a bad state.
                System.out.println("Unexpected condition writing message: " + ioe);
                tryToClose(session, new CloseReason(CloseCodes.UNEXPECTED_CONDITION, trimReason(ioe.toString())));
            }
        }
        return false;
    }

    /**
     * {@code CloseReason} can include a value, but the length of the text is
     * limited.
     *
     * @param message
     *            String to trim
     * @return a string no longer than 123 characters.
     */
    private static String trimReason(String message) {
        return message.length() > 123 ? message.substring(0, 123) : message;
    }

    /**
     * Try to close the WebSocket session and give a reason for doing so.
     *
     * @param s
     *            Session to close
     * @param reason
     *            {@link CloseReason} the WebSocket is closing.
     */
    public void tryToClose(Session s, CloseReason reason) {
        try {
            s.close(reason);
        } catch (IOException e) {
            tryToClose(s);
        }
    }

    /**
     * Try to close a {@code Closeable} (usually once an error has already
     * occurred).
     *
     * @param c
     *            Closable to close
     */
    public void tryToClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e1) {
            }
        }
    }

}