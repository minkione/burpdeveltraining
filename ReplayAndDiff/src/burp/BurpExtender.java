/*
 * ReplayAndDiff - Replay a scan with a fresh session and diff the results
 *
 * Copyright (c) 2017 Luca Carettoni - Doyensec LLC.
 */
package burp;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Iterator;

/*
 * This extension can be executed in headless mode. Start burp using -Djava.awt.headless=true
 */
public class BurpExtender implements IBurpExtender {

    //Configuration
    static final String MONGO_HOST = "127.0.0.1";
    static final int MONGO_PORT = 27017;
    static final String REPORT_DIR = "/tmp/";

    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {

        this.callbacks = callbacks;
        helpers = callbacks.getHelpers();

        callbacks.setExtensionName("ReplayAndDiff");
        System.out.println("\n\n:: ReplayAndDiff Headless Extension ::\n\n");

        //Retrieve site info and login request from MongoDB
        MongoClient mongo = null;
        try {
            mongo = new MongoClient(MONGO_HOST, MONGO_PORT);
        } catch (UnknownHostException ex) {
            System.err.println("[!] MongoDB Connection Error: " + ex.toString());
        }

        DB db = mongo.getDB("sitelogger");
        DBCollection table = db.getCollection("login");
        DBCursor cursor = table.find();

        String host = null;
        while (cursor.hasNext()) {
            DBObject entry = cursor.next();
            //Replay the HTTP request and save the fresh cookie in Burp's Cookies JAR
            host = (String) entry.get("host");
            System.out.println("[*] Retrieving record for: " + host);
            byte[] response = callbacks.makeHttpRequest(host, ((Double) entry.get("port")).intValue(), "https".equals((String) entry.get("protocol")), b64d((String) entry.get("request")));
            Iterator<ICookie> cookies = helpers.analyzeResponse(response).getCookies().iterator();
            while (cookies.hasNext()) {
                try {
                    ICookie cookie = cookies.next();
                    System.out.println("[*] Obtained cookie: " + cookie.getName() + ":" + cookie.getValue());
                    callbacks.updateCookieJar(cookie);
                } catch (NullPointerException npe) {
                    System.out.println("[!] Missing cookie attributes - e.g. domain not set");
                }
            }
        }

        //Replay a scan on all URLs previously saved for the same site
        if (host != null) {
            table = db.getCollection(host.replaceAll("\\.", "_") + "_site");
        } else {
            throw new NullPointerException();
        }
        cursor = table.find();
        URL website = null;
        while (cursor.hasNext()) {
            DBObject entry = cursor.next();
            //Add host in scope. This is meant to prevent popup since the extension is running headless
            try {
                website = new URL(((String) entry.get("protocol")) + "://" + ((String) entry.get("host")));
                callbacks.includeInScope(website);

                //Execute passive and active scans
                callbacks.doActiveScan(((String) entry.get("host")), ((int) entry.get("port")), "https".equals((String) entry.get("protocol")), b64d((String) entry.get("request")));
                callbacks.doPassiveScan(((String) entry.get("host")), ((int) entry.get("port")), "https".equals((String) entry.get("protocol")), b64d((String) entry.get("request")), b64d((String) entry.get("response")));

            } catch (MalformedURLException ex) {
                System.err.println("[!] Malformed website URL: " + ex.toString());
            } catch (NullPointerException ex) {
                System.err.println("[!] Missing request or response: " + ex.toString());
            }
        }

        try {
            System.out.println("[*] Pausing extension...");
            // HOMEWORK - Build a queuing system to check scans status and confirm once all scans are done
            Thread.sleep(10000);
            System.out.println("[*] Resuming extension...");
        } catch (InterruptedException ex) {
            System.err.println("[!] InterruptedException: " + ex.toString());
        }

        table = db.getCollection(host.replaceAll("\\.", "_") + "_vuln");
        BasicDBObject searchQuery = null;
        IScanIssue[] allVulns = null;
        boolean newFinding = false;

        //Obtain the new scan findings
        if (website != null) {
            allVulns = callbacks.getScanIssues(website.toString());

            for (IScanIssue allVuln : allVulns) {
                //Diff new and old scan results.
                searchQuery = new BasicDBObject();
                searchQuery.put("type", allVuln.getIssueType());
                searchQuery.put("name", allVuln.getIssueName());
                searchQuery.put("URL", allVuln.getUrl().toString());
                System.out.println("[*] Looking for: " + searchQuery.toString());
                cursor = table.find(searchQuery);
                if (cursor.size() == 0) {
                    //There's at least one new finding
                    System.out.println("[*] Got a new finding!");
                    newFinding = true;
                }
            }

            if (newFinding) {
                System.out.println("[*] New findings! Generating report...");
                callbacks.generateScanReport("HTML", allVulns, new File(REPORT_DIR + host.replaceAll("\\.", "_") + System.currentTimeMillis() + ".html"));
            } else {
                System.out.println("[*] Scan and diff completed. No new results.");
            }

        } else {
            throw new NullPointerException();
        }
        System.out.println("[*] Ready to shutdown...Bye!");
        callbacks.exitSuite(false);
    }

    /* Utility method to Base64 decode */
    private byte[] b64d(String input) {

        if (input != null) {
            return helpers.base64Decode(input);
        }
        return new byte[0];
    }
}
