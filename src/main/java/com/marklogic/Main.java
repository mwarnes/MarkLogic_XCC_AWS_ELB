package com.marklogic;

import com.marklogic.aws.ELBSSLConnectionProvider;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.ContentSourceFactory;
import com.marklogic.xcc.SecurityOptions;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.XccConfigException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    private static Logger logger;

    public static void main(String[] args) throws URISyntaxException, XccConfigException, RequestException, NoSuchAlgorithmException, KeyManagementException {

        logger = Logger.getLogger(Main.class.getName());
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINEST);

        // Create MarkLogic connection
        logger.info("Open MarkLogic connection ");

        // Required for HTTP stickiness in a Load Balancer
        System.setProperty("xcc.httpcompliant","true");

        // Simple SSL Context with TrustAll hosts
        SSLContext sslContext = SSLContext.getInstance("TLS");
        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }
        };
        sslContext.init(null, new TrustManager[] { tm }, null);
        SecurityOptions so = new SecurityOptions(sslContext);

//        ELBConnectionProvider elbProvider =
//                new ELBConnectionProvider("MartinW3n-ElasticL-13NX501DWLI3S-1239802618.eu-west-1.elb.amazonaws.com",8006);

        ELBSSLConnectionProvider elbSSLProvider =
            new ELBSSLConnectionProvider("MartinW3n-ElasticL-13NX501DWLI3S-1239802618.eu-west-1.elb.amazonaws.com",8008, so);

        ContentSource contentSource =
                ContentSourceFactory.newContentSource(elbSSLProvider,"admin","admin","Documents");

        // Main outer loop
        for (int loop = 1; loop <= 600000; loop++) {
            // Create a Session and set the transaction mode to trigger
            // multi-statement transaction use.
            Session updateSession = contentSource.newSession();
            updateSession.setTransactionMode(Session.TransactionMode.UPDATE);
            try {
                // Loop around adding some documents
                for (int counter = 1; counter <= 2; counter++) {
                    // The request starts a new, multi-statement transaction.
                    updateSession.submitRequest(updateSession.newAdhocQuery(
                            "xdmp:document-insert('/docs/mst1.xml', <data/>)"));
                    // The request starts a new, multi-statement transaction.
                    updateSession.submitRequest(updateSession.newAdhocQuery(
                            "xdmp:document-insert('/docs/mst2.xml', <data/>)"));
                }
                // After commit, updates are visible to other transactions.
                // Commit ends the transaction after current stmt completes.
                updateSession.commit();
                Thread.sleep(5000);
            } catch (Exception e) {
                // In event of failure roll back transaction
                e.printStackTrace();
                updateSession.rollback();
            }
            //Loop around and repeat
            updateSession.close();
        }

        // Release the MarkLogic client
        logger.info("Ended....");

    }
}
