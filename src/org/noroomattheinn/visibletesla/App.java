/*
 * App.java - Copyright(c) 2013 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: Aug 30, 2013
 */
package org.noroomattheinn.visibletesla;

import com.sun.net.httpserver.BasicAuthenticator;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Properties;
import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.input.InputEvent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.apache.commons.codec.digest.DigestUtils;
import org.noroomattheinn.tesla.Tesla;
import org.noroomattheinn.utils.PWUtils;
import org.noroomattheinn.utils.RestHelper;
import org.noroomattheinn.utils.ThreadManager;
import org.noroomattheinn.utils.TrackedObject;
import org.noroomattheinn.utils.Utils;

import static org.noroomattheinn.tesla.Tesla.logger;
import static org.noroomattheinn.utils.Utils.timeSince;

/**
 * App - Stores state about the app for use across the app. This is a singleton
 * that is created at app startup.
 *
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */
class App {
/*------------------------------------------------------------------------------
 *
 * Constants and Enums
 * 
 *----------------------------------------------------------------------------*/
    
    private static final String AppPropertiesFile = 
            "org/noroomattheinn/visibletesla/VT.properties";
    static final String LastExportDirKey = "APP_LAST_EXPORT_DIR";
    
/*------------------------------------------------------------------------------
 *
 * Internal State
 * 
 *----------------------------------------------------------------------------*/
    
    private final File          appFilesFolder;
    private final Prefs         prefs;
    private final Application   fxApp;
    private final PWUtils       pwUtils = new PWUtils();
    private       byte[]        encPW, salt;
    private       long          lastEventTime;    
    
    private static Properties appProperties = 
        loadAppProperties(
            App.class.getClassLoader().getResourceAsStream(AppPropertiesFile));
    
/*------------------------------------------------------------------------------
 *
 * Package-wide State
 * 
 *----------------------------------------------------------------------------*/
    
    final AppAPI            api;
    final Tesla             tesla;
    final Stage             stage;
    final ProgressListener  progressListener;
    final TrackedObject<String>  schedulerActivity;
    
    
/*==============================================================================
 * -------                                                               -------
 * -------         Package Internal Interface To This Class              ------- 
 * -------                                                               -------
 *============================================================================*/
    
    /**
     * Called once when the app starts in order to create the singleton. 
     * @param fxApp The JavaFX Application object
     * @param stage The JavaFX stage corresponding to our base window
     * @param prefs The application preferences
     * @return      The newly created singleton.
     */
    App(Application fxApp, Stage stage, final Prefs prefs) {
        this.fxApp = fxApp;
        this.stage = stage;
        this.prefs = prefs;
        this.schedulerActivity = new TrackedObject<>("");
        this.api = new AppAPI(schedulerActivity);
        this.lastEventTime = System.currentTimeMillis();

        api.mode.addTracker(new Runnable() {
            @Override public void run() {
                logger.finest("App Mode changed to " + api.mode.get());
                prefs.persist("InactivityMode", api.mode.get().name());
                if (api.mode.get() == AppAPI.Mode.StayAwake) { api.setActive(); }
            }
        });

        api.state.addTracker(new Runnable() {
            @Override public void run() {
                logger.finest("App State changed to " + api.state.get());
                if (api.state.get() == AppAPI.State.Active) {
                    logger.info("Resetting Idle start time to now");
                    lastEventTime = System.currentTimeMillis();
                }
            }
        });

        appFilesFolder = Utils.ensureAppFilesFolder(productName());
        Utils.setupLogger(appFilesFolder, "visibletesla", logger, prefs.getLogLevel());

        if (prefs.enableProxy.get()) {
            // Enable the proxy at the lowest level so all services use it
            RestHelper.setDefaultProxy(prefs.proxyHost.get(), prefs.proxyPort.get());
        }
        tesla = new Tesla();

        this.progressListener = new ProgressListener(prefs.submitAnonFailure, getAppID());
        
        internalizePW(prefs.authCode.get());
    }
    
    static String productName() { return appProperties.getProperty("APP_NAME"); }
    static String productVersion() { return appProperties.getProperty("APP_VERSION"); }
    
    void showDocument(String doc) { fxApp.getHostServices().showDocument(doc); }
    HostServices getHostServices() { return fxApp.getHostServices(); }
    
    /**
     * Establish ourselves as the only running instance of the app for
     * a particular vehicle id.
     * @return  true is we got the lock
     *          false if another instance is already running
     */
    boolean lock(String vin) {
        return (Utils.obtainLock(vin + ".lck", appFilesFolder));
    }
    
    /**
     * Get the system folder in which app related files are to be stored.
     * @return  The folder in which app related files are to be stored
     */
    File appFileFolder() { return appFilesFolder; }
    
    final String getAppID() {
        String appID = "Unidentified";
        try {
            InetAddress ip = InetAddress.getLocalHost();
            NetworkInterface network = NetworkInterface.getByInetAddress(ip);
            if (network != null) {
                byte[] mac = network.getHardwareAddress();
                appID = DigestUtils.sha256Hex(mac);
            }
        } catch (UnknownHostException | SocketException e) {
            logger.warning("Unable to generate an AppID: " + e.getMessage());
        }
        return appID;
    }
    

    /**
     * Add a tracker to a TrackedObject, but ensure it will run on the
     * FX Application Thread.
     * @param t The tracked object
     * @param r The Runnable to execute on the FXApplicationThread
     */
    static void addTracker(TrackedObject t, final Runnable r) {
        t.addTracker(new Runnable() {
            @Override public void run() {
                Platform.runLater(r);
            }
        });
    }
    
    /**
     * Set the mode based on the value in the persistent store
     */
    void restoreMode() {
        String modeName = prefs.storage().get(
                "InactivityMode", AppAPI.Mode.StayAwake.name());
        // Handle obsolete values or changed names
        switch (modeName) {
            case "Sleep": modeName = "AllowSleeping"; break;    // Name Changed
            case "Awake": modeName = "StayAwake"; break;        // Name Changed
            case "AllowDaydreaming": modeName = "Awake"; break; // Obsolete
            case "Daydream": modeName = "Awake"; break;         // Obsolete
            }
        api.mode.set(AppAPI.Mode.valueOf(modeName));
    }
    
/*------------------------------------------------------------------------------
 *
 * Support for authenticating to web services
 * 
 *----------------------------------------------------------------------------*/
    
    /**
     * Set the password used by the RESTServer. If no password is supplied, 
     * a random one will be chosen meaning there is effectively no access to
     * the server.
     * @param   pw  The new password
     * @return  An external representation of the salted password that can be
     *          stored safely in a data file.
     */
    final String setPW(String pw) {
        if (pw == null || pw.isEmpty()) { // Choose a random value!
            pw = String.valueOf(Math.floor(Math.random()*100000));   
        }
        salt = pwUtils.generateSalt();
        encPW = pwUtils.getEncryptedPassword(pw, salt);
        return pwUtils.externalRep(salt, encPW);
    }

    BasicAuthenticator authenticator = new BasicAuthenticator("VisibleTesla") {
        @Override public boolean checkCredentials(String user, String pwd) {
            if (!user.equals("VT")) return false;
            if (encPW == null || salt == null) return false;
            return pwUtils.authenticate(pwd, encPW, salt);
        }
    };
    
    /**
     * Initialize the password and salt from previously generated values.
     * @param externalForm  An external representation of the password and
     *                      salt that was previously returned by an invocation
     *                      of setPW()
     */
    private void internalizePW(String externalForm) {
        // Break down the external representation into the salt and password
        List<byte[]> internalForm = (new PWUtils()).internalRep(externalForm);
        salt = internalForm.get(0);
        encPW = internalForm.get(1);
    }

/*------------------------------------------------------------------------------
 *
 * PRIVATE - Methods and classes to track activity
 * 
 *----------------------------------------------------------------------------*/
    
    /**
     * Begin watching for user inactivity (keyboard input, mouse movements, etc.)
     * on any of the specified Tabs.
     * @param tabs  Watch for user activity targeted to any of these tabs.
     */
    void watchForUserActivity(List<Tab> tabs) {
        for (Tab t : tabs) {
            Node n = t.getContent();
            n.addEventFilter(KeyEvent.ANY, new EventPassThrough());
            n.addEventFilter(MouseEvent.MOUSE_PRESSED, new EventPassThrough());
            n.addEventFilter(MouseEvent.MOUSE_RELEASED, new EventPassThrough());
        }
        ThreadManager.get().launch(
                new InactivityThread(60L * 1000L * prefs.idleThresholdInMinutes.get()),
                "Inactivity");
    }

    private class InactivityThread implements Runnable {
        long idleThreshold;
        
        InactivityThread(long threshold) { this.idleThreshold = threshold; }
        
        @Override public void run() {
            while (true) {
                ThreadManager.get().sleep(60 * 1000);
                if (ThreadManager.get().shuttingDown()) {
                    return;
                }
                if (timeSince(lastEventTime) > idleThreshold && api.allowingSleeping()) {
                    api.state.update(AppAPI.State.Idle);
                }
            }
        }
    }

    private class EventPassThrough implements EventHandler<InputEvent> {
        @Override public void handle(InputEvent ie) {
            lastEventTime = System.currentTimeMillis();
            api.state.update(AppAPI.State.Active);
        }
    }

    private static Properties loadAppProperties(InputStream is) {
        Properties p = new Properties();
        try {
            p.load(is);
        } catch (IOException ex) {
            logger.warning("Couldn't load app properties file: " + ex);
        }
        return p;
    }

}
