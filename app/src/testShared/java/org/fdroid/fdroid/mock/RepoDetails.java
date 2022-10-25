package org.fdroid.fdroid.mock;

import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.Apk;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.data.RepoPushRequest;
import org.fdroid.fdroid.data.RepoXMLHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import androidx.annotation.NonNull;

import static org.junit.Assert.fail;

public class RepoDetails implements RepoXMLHandler.IndexReceiver {
    public static final String TAG = "RepoDetails";

    public String name;
    public String description;
    public String signingCert;
    public int maxAge;
    public int version;
    public long timestamp;
    public String icon;
    public String[] mirrors;

    public final List<Apk> apks = new ArrayList<>();
    public final List<App> apps = new ArrayList<>();
    public final List<RepoPushRequest> repoPushRequestList = new ArrayList<>();

    @Override
    public void receiveRepo(String name, String description, String signingCert, int maxage,
                            int version, long timestamp, String icon, String[] mirrors) {
        this.name = name;
        this.description = description;
        this.signingCert = signingCert;
        this.maxAge = maxage;
        this.version = version;
        this.timestamp = timestamp;
        this.icon = icon;
        this.mirrors = mirrors;
    }

    @Override
    public void receiveApp(App app, List<Apk> packages) {
        apks.addAll(packages);
        apps.add(app);
    }

    @Override
    public void receiveRepoPushRequest(RepoPushRequest repoPushRequest) {
        repoPushRequestList.add(repoPushRequest);
    }

    @NonNull
    public static RepoDetails getFromFile(InputStream inputStream, int pushRequests) {
        try {
            XMLReader reader = Utils.newXMLReaderInstance();
            RepoDetails repoDetails = new RepoDetails();
            MockRepo mockRepo = new MockRepo(100, pushRequests);
            RepoXMLHandler handler = new RepoXMLHandler(mockRepo, repoDetails);
            reader.setContentHandler(handler);
            InputSource is = new InputSource(new BufferedInputStream(inputStream));
            reader.parse(is);
            return repoDetails;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
            fail();

            // Satisfies the compiler, but fail() will always throw a runtime exception so we never
            // reach this return statement.
            return null;
        }
    }

}

