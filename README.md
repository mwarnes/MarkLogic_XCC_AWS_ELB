**Sample MarkLogic XCC AWS ELB ConnectionProvider**

The XML Contentbase Connector (XCC) is an interface to communicate with a MarkLogic Server from a Java middleware application layer. It provides APIs for

* Evaluating stored XQuery programs
* Dynamic construction and evaluation of XQuery programs from Java code
* Type marshaling
* Document management and streaming inserts


**Overview**

The Default ConnectionProvider provided by the XCC library uses a pool of connected session to reduce the overhead of creating a new session for each request. For the significant proportion of implementations the use of a connection pool does not pose any challenges, however, in the case where an Amazon Web Services (AWS) Elastic Load Balancer is being used in front of a MarkLogic Cluster problems can occur. This is because AWS ELB IP addresses are dynamic and can change over time, in addition, AWS can scale the number of Elastic Load Balancers depending on load.

**Cause**

When an XCC application creates a ContentSource object using an AWS ELB hostname the supplied ConnectionProvider uses the first hostname/IP address returned from the DNS query to create a session in the pool. If the tasks being performed by the application are long running it is possible that the ELB IP address may change or be removed over time and when this occurs an application exception will occur continually until the application is restarted and a new pool is created.

**Solution**

This project contains a sample ConnectionProvider that recognizes when the ELB IP Address has changed, a new IP address is added or a stale IP Address is removed and updates the pool address accordingly without requiring the application to be restarted.

There are two Connection Providers available, **ELBConnectionProvider** for standard connections and **ELBSSLConnectionProvider** for Secure TLSv1.2 connections.

**Example**

1. Create a ContentSource using a plain ELBConnectionProvider

````
        ELBConnectionProvider elbProvider =
                new ELBConnectionProvider("MarkLog1-ElasticL-13NX501DWLI3S-1239802618.eu-west-1.elb.amazonaws.com",8006);

        ContentSource contentSource =
                ContentSourceFactory.newContentSource(elbProvider,"admin","admin","Documents");
````


2. Create a ContentSource using a TLSv1.2 ELBSSLConnectionProvider

````
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
        
        ELBSSLConnectionProvider elbSSLProvider =
            new ELBSSLConnectionProvider("MartinW3n-ElasticL-13NX501DWLI3S-1239802618.eu-west-1.elb.amazonaws.com",8008, so);

        ContentSource contentSource =
                ContentSourceFactory.newContentSource(elbSSLProvider,"admin","admin","Documents");
```` 

Sample output when the IP Address associated with the ELB changes shows address being dynamically updated.

````
Aug 29, 2017 10:10:33 AM com.marklogic.Main main
INFO: Open MarkLogic connection 
Aug 29, 2017 10:10:33 AM com.marklogic.ELBConnectionProvider <init>
INFO: constructing new ELBConnectionProvider for MartinW3n-ElasticL-13NX501DWLI3S-1239802618.eu-west-1.elb.amazonaws.com/54.228.210.164:8006
Aug 29, 2017 8:11:39 PM com.marklogic.ELBConnectionProvider getAddress
INFO: Cached InetAddress 54.228.210.164
Aug 29, 2017 8:11:39 PM com.marklogic.ELBConnectionProvider getAddress
INFO: Current InetAddress 54.247.182.147
Aug 29, 2017 8:11:39 PM com.marklogic.ELBConnectionProvider getAddress
INFO: Current and Cached IP Addresses do not match... updating...
Aug 29, 2017 8:11:39 PM com.marklogic.ELBConnectionProvider getAddress
INFO: Update complete, time=46m/s     
````

**Notes**

1. The sample ELB ConnectionProviders are provided "asis" and are not supported by MarkLogic. It is your responsibility to review and test thoroughly before using them in a production environment.
2. Both ConnectionProviders, as with the supplied provider, implement the SingleHostAddress class which does not make use of the multiple IP address redundancy supplied by AWS ELB. Time permitting I may update this in the future.
3. Feel free to ForK, Improve and Share any improvements or updates.



          