/*******************************************************************************
 * Copyright (c) 2011, 2017 Eurotech and others
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Eurotech
 *     Red Hat Inc - Clean up kura properties handling
 *******************************************************************************/

package org.eclipse.kura.core.deployment;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.cloud.Cloudlet;
import org.eclipse.kura.cloud.CloudletTopic;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.core.deployment.download.DeploymentPackageDownloadOptions;
import org.eclipse.kura.core.deployment.download.DownloadCountingOutputStream;
import org.eclipse.kura.core.deployment.download.DownloadFileUtilities;
import org.eclipse.kura.core.deployment.download.impl.DownloadImpl;
import org.eclipse.kura.core.deployment.hook.DeploymentHookManager;
import org.eclipse.kura.core.deployment.install.DeploymentPackageInstallOptions;
import org.eclipse.kura.core.deployment.install.InstallImpl;
import org.eclipse.kura.core.deployment.uninstall.DeploymentPackageUninstallOptions;
import org.eclipse.kura.core.deployment.uninstall.UninstallImpl;
import org.eclipse.kura.core.deployment.xml.XmlBundle;
import org.eclipse.kura.core.deployment.xml.XmlBundleInfo;
import org.eclipse.kura.core.deployment.xml.XmlBundles;
import org.eclipse.kura.core.deployment.xml.XmlDeploymentPackage;
import org.eclipse.kura.core.deployment.xml.XmlDeploymentPackages;
import org.eclipse.kura.core.util.ThrowableUtil;
import org.eclipse.kura.data.DataTransportService;
import org.eclipse.kura.deployment.hook.DeploymentHook;
import org.eclipse.kura.marshalling.Marshaller;
import org.eclipse.kura.message.KuraPayload;
import org.eclipse.kura.message.KuraRequestPayload;
import org.eclipse.kura.message.KuraResponsePayload;
import org.eclipse.kura.ssl.SslManagerService;
import org.eclipse.kura.system.SystemService;
import org.eclipse.kura.util.service.ServiceUtil;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.ComponentException;
import org.osgi.service.deploymentadmin.BundleInfo;
import org.osgi.service.deploymentadmin.DeploymentAdmin;
import org.osgi.service.deploymentadmin.DeploymentPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudDeploymentHandlerV2 extends Cloudlet implements ConfigurableComponent {

    private static final Logger logger = LoggerFactory.getLogger(CloudDeploymentHandlerV2.class);
    public static final String APP_ID = "DEPLOY-V2";

    private static final String DPA_CONF_PATH_PROPNAME = "dpa.configuration";
    private static final String PACKAGES_PATH_PROPNAME = "kura.packages";
    private static final String KURA_DATA_DIR = "kura.data";

    public static final String RESOURCE_PACKAGES = "packages";
    public static final String RESOURCE_BUNDLES = "bundles";

    /* EXEC */
    public static final String RESOURCE_DOWNLOAD = "download";
    public static final String RESOURCE_INSTALL = "install";
    public static final String RESOURCE_UNINSTALL = "uninstall";
    public static final String RESOURCE_CANCEL = "cancel";
    public static final String RESOURCE_START = "start";
    public static final String RESOURCE_STOP = "stop";

    /* Metrics in the REPLY to RESOURCE_DOWNLOAD */
    public static final String METRIC_DOWNLOAD_STATUS = "download.status";
    public static final String METRIC_REQUESTER_CLIENT_ID = "requester.client.id";

    /**
     * Enum representing the different status of the download process
     *
     * {@link DeploymentAgentService.DOWNLOAD_STATUS.PROGRESS} Download in
     * progress {@link DeploymentAgentService.DOWNLOAD_STATUS.COMPLETE} Download
     * completed {@link DeploymentAgentService.DOWNLOAD_STATUS.FAILED} Download
     * failed
     */
    public enum DOWNLOAD_STATUS {
        IN_PROGRESS("IN_PROGRESS"),
        COMPLETED("COMPLETED"),
        FAILED("FAILED"),
        ALREADY_DONE("ALREADY DONE"),
        CANCELLED("CANCELLED");

        private final String status;

        DOWNLOAD_STATUS(String status) {
            this.status = status;
        }

        public String getStatusString() {
            return this.status;
        }
    }

    public enum INSTALL_STATUS {
        IDLE("IDLE"),
        IN_PROGRESS("IN_PROGRESS"),
        COMPLETED("COMPLETED"),
        FAILED("FAILED"),
        ALREADY_DONE("ALREADY DONE");

        private final String status;

        INSTALL_STATUS(String status) {
            this.status = status;
        }

        public String getStatusString() {
            return this.status;
        }
    }

    public enum UNINSTALL_STATUS {
        IDLE("IDLE"),
        IN_PROGRESS("IN_PROGRESS"),
        COMPLETED("COMPLETED"),
        FAILED("FAILED"),
        ALREADY_DONE("ALREADY DONE");

        private final String status;

        UNINSTALL_STATUS(String status) {
            this.status = status;
        }

        public String getStatusString() {
            return this.status;
        }
    }

    private CloudDeploymentHandlerV2Options componentOptions;

    private static String pendingPackageUrl = null;
    private static DownloadImpl downloadImplementation;
    private static UninstallImpl uninstallImplementation;
    public static InstallImpl installImplementation;

    private SslManagerService sslManagerService;
    private DeploymentAdmin deploymentAdmin;
    private SystemService systemService;
    private DeploymentHookManager deploymentHookManager;

    private static ExecutorService executor = Executors.newSingleThreadExecutor();

    private Future<?> downloaderFuture;
    private Future<?> installerFuture;

    private BundleContext bundleContext;

    private DataTransportService dataTransportService;

    private String dpaConfPath;
    private String packagesPath;

    private DeploymentPackageDownloadOptions downloadOptions;

    private boolean isInstalling = false;
    private DeploymentPackageInstallOptions installOptions;

    private String pendingUninstPackageName;
    private String installVerificationDir;

    public CloudDeploymentHandlerV2() {
        super(APP_ID);
    }

    // ----------------------------------------------------------------
    //
    // Dependencies
    //
    // ----------------------------------------------------------------

    public void setSslManagerService(SslManagerService sslManagerService) {
        this.sslManagerService = sslManagerService;
    }

    public void unsetSslManagerService(SslManagerService sslManagerService) {
        this.sslManagerService = null;
    }

    protected void setDeploymentAdmin(DeploymentAdmin deploymentAdmin) {
        this.deploymentAdmin = deploymentAdmin;
    }

    protected void unsetDeploymentAdmin(DeploymentAdmin deploymentAdmin) {
        this.deploymentAdmin = null;
    }

    public void setDataTransportService(DataTransportService dataTransportService) {
        this.dataTransportService = dataTransportService;
    }

    public void unsetDataTransportService(DataTransportService dataTransportService) {
        this.dataTransportService = null;
    }

    public void setSystemService(SystemService systemService) {
        this.systemService = systemService;
    }

    public void unsetSystemService(SystemService systemService) {
        this.systemService = null;
    }

    public void setDeploymentHookManager(DeploymentHookManager deploymentHookManager) {
        this.deploymentHookManager = deploymentHookManager;
    }

    public void unsetDeploymentHookManager(DeploymentHookManager deploymentHookManager) {
        this.deploymentHookManager = null;
    }

    // ----------------------------------------------------------------
    //
    // Activation APIs
    //
    // ----------------------------------------------------------------

    protected void activate(ComponentContext componentContext, Map<String, Object> properties) {
        logger.info("Cloud Deployment v2 is starting");
        super.activate(componentContext);
        updated(properties);

        this.bundleContext = componentContext.getBundleContext();

        this.dpaConfPath = System.getProperty(DPA_CONF_PATH_PROPNAME);
        if (this.dpaConfPath == null || this.dpaConfPath.isEmpty()) {
            throw new ComponentException("The value of '" + DPA_CONF_PATH_PROPNAME + "' is not defined");
        }

        final Properties kuraProperties = this.systemService.getProperties();

        this.packagesPath = kuraProperties.getProperty(PACKAGES_PATH_PROPNAME);
        if (this.packagesPath == null || this.packagesPath.isEmpty()) {
            throw new ComponentException("The value of '" + PACKAGES_PATH_PROPNAME + "' is not defined");
        }
        if (kuraProperties.getProperty(PACKAGES_PATH_PROPNAME) != null
                && "kura/packages".equals(kuraProperties.getProperty(PACKAGES_PATH_PROPNAME).trim())) {
            kuraProperties.setProperty(PACKAGES_PATH_PROPNAME, "/opt/eclipse/kura/kura/packages");
            this.packagesPath = kuraProperties.getProperty(PACKAGES_PATH_PROPNAME);
            logger.warn("Overridding invalid kura.packages location");
        }

        String kuraDataDir = kuraProperties.getProperty(KURA_DATA_DIR);

        installImplementation = new InstallImpl(this, kuraDataDir);
        installImplementation.setPackagesPath(this.packagesPath);
        installImplementation.setDpaConfPath(this.dpaConfPath);
        installImplementation.setDeploymentAdmin(this.deploymentAdmin);
        installImplementation.sendInstallConfirmations();
    }

    protected void updated(Map<String, Object> properties) {
        this.componentOptions = new CloudDeploymentHandlerV2Options(properties);
        final Properties associations = new Properties();
        try {
            associations.load(new StringReader(this.componentOptions.getHookAssociations()));
        } catch (Exception e) {
            logger.warn("failed to parse hook associations from configuration", e);
        }
        this.deploymentHookManager.updateAssociations(associations);
    }

    @Override
    protected void deactivate(ComponentContext componentContext) {
        logger.info("Bundle " + APP_ID + " is deactivating!");
        if (this.downloaderFuture != null) {
            this.downloaderFuture.cancel(true);
        }

        if (this.installerFuture != null) {
            this.installerFuture.cancel(true);
        }

        this.bundleContext = null;
    }

    // ----------------------------------------------------------------
    //
    // Public methods
    //
    // ----------------------------------------------------------------

    public void publishMessage(DeploymentPackageOptions options, KuraPayload messagePayload, String messageType) {
        try {
            String messageTopic = new StringBuilder("NOTIFY/").append(options.getClientId()).append("/")
                    .append(messageType).toString();

            getCloudApplicationClient().controlPublish(options.getRequestClientId(), messageTopic, messagePayload, 1,
                    DFLT_RETAIN, DFLT_PRIORITY);
        } catch (KuraException e) {
            logger.error("Error publishing response for command {} {}", messageType, e);
        }
    }

    // ----------------------------------------------------------------
    //
    // Protected methods
    //
    // ----------------------------------------------------------------

    @Override
    protected void doGet(CloudletTopic reqTopic, KuraRequestPayload reqPayload, KuraResponsePayload respPayload)
            throws KuraException {

        String[] resources = reqTopic.getResources();

        if (resources == null || resources.length == 0) {
            logger.error("Bad request topic: {}", reqTopic.toString());
            logger.error("Expected one resource but found {}", resources != null ? resources.length : "none");
            respPayload.setResponseCode(KuraResponsePayload.RESPONSE_CODE_BAD_REQUEST);
            return;
        }

        if (resources[0].equals(RESOURCE_DOWNLOAD)) {
            doGetDownload(respPayload);
        } else if (resources[0].equals(RESOURCE_INSTALL)) {
            doGetInstall(respPayload);
        } else if (resources[0].equals(RESOURCE_PACKAGES)) {
            doGetPackages(respPayload);
        } else if (resources[0].equals(RESOURCE_BUNDLES)) {
            doGetBundles(respPayload);
        } else {
            logger.error("Bad request topic: {}", reqTopic.toString());
            logger.error("Cannot find resource with name: {}", resources[0]);
            respPayload.setResponseCode(KuraResponsePayload.RESPONSE_CODE_NOTFOUND);
            return;
        }
    }

    @Override
    protected void doExec(CloudletTopic reqTopic, KuraRequestPayload reqPayload, KuraResponsePayload respPayload)
            throws KuraException {

        String[] resources = reqTopic.getResources();

        if (resources == null || resources.length == 0) {
            logger.error("Bad request topic: {}", reqTopic.toString());
            logger.error("Expected one resource but found {}", resources != null ? resources.length : "none");
            respPayload.setResponseCode(KuraResponsePayload.RESPONSE_CODE_BAD_REQUEST);
            return;
        }

        if (resources[0].equals(RESOURCE_DOWNLOAD)) {
            doExecDownload(reqPayload, respPayload);
        } else if (resources[0].equals(RESOURCE_INSTALL)) {
            doExecInstall(reqPayload, respPayload);
        } else if (resources[0].equals(RESOURCE_UNINSTALL)) {
            doExecUninstall(reqPayload, respPayload);
        } else if (resources[0].equals(RESOURCE_START)) {
            String bundleId = resources.length >= 2 ? resources[1] : null; // no checking is done before
            doExecStartStopBundle(respPayload, true, bundleId);
        } else if (resources[0].equals(RESOURCE_STOP)) {
            String bundleId = resources.length >= 2 ? resources[1] : null; // no checking is done before
            doExecStartStopBundle(respPayload, false, bundleId);
        } else {
            logger.error("Bad request topic: {}", reqTopic.toString());
            logger.error("Cannot find resource with name: {}", resources[0]);
            respPayload.setResponseCode(KuraResponsePayload.RESPONSE_CODE_NOTFOUND);
            return;
        }
    }

    @Override
    protected void doDel(CloudletTopic reqTopic, KuraRequestPayload reqPayload, KuraResponsePayload respPayload)
            throws KuraException {

        String[] resources = reqTopic.getResources();

        if (resources == null || resources.length == 0) {
            logger.error("Bad request topic: {}", reqTopic.toString());
            logger.error("Expected one resource but found {}", resources != null ? resources.length : "none");
            respPayload.setResponseCode(KuraResponsePayload.RESPONSE_CODE_BAD_REQUEST);
            return;
        }

        if (resources[0].equals(RESOURCE_DOWNLOAD)) {
            doDelDownload(reqPayload, respPayload);
        } else {
            logger.error("Bad request topic: {}", reqTopic.toString());
            logger.error("Cannot find resource with name: {}", resources[0]);
            respPayload.setResponseCode(KuraResponsePayload.RESPONSE_CODE_NOTFOUND);
            return;
        }
    }

    protected DownloadImpl createDownloadImpl(final DeploymentPackageDownloadOptions options) {
        DownloadImpl downloadImplementation = new DownloadImpl(options, this);
        return downloadImplementation;
    }

    protected UninstallImpl createUninstallImpl() {
        UninstallImpl uninstallImplementation = new UninstallImpl(this, this.deploymentAdmin);

        return uninstallImplementation;
    }

    protected File getDpDownloadFile(final DeploymentPackageInstallOptions options) throws IOException {
        return DownloadFileUtilities.getDpDownloadFile(options);
    }

    // ----------------------------------------------------------------
    //
    // Private methods
    //
    // ----------------------------------------------------------------

    private void doDelDownload(KuraRequestPayload request, KuraResponsePayload response) {

        try {
            DownloadCountingOutputStream downloadHelper = downloadImplementation.getDownloadHelper();
            if (downloadHelper != null) {
                downloadHelper.cancelDownload();
                downloadImplementation.deleteDownloadedFile();
            }
        } catch (Exception ex) {
            String errMsg = "Error cancelling download!";
            logger.warn(errMsg, ex);
            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setTimestamp(new Date());
            try {
                response.setBody(errMsg.getBytes("UTF-8"));
                response.setException(ex);
            } catch (UnsupportedEncodingException uee) {
            }
        }

    }

    private void checkHook(DeploymentPackageInstallOptions options) {
        if (options.getRequestType() != null && options.getDeploymentHook() == null) {
            throw new IllegalStateException("No DeploymentHook is currently associated to request type "
                    + options.getRequestType() + ", aborting operation");
        }
    }

    private void doExecDownload(KuraRequestPayload request, KuraResponsePayload response) {

        final DeploymentPackageDownloadOptions options;
        try {
            options = new DeploymentPackageDownloadOptions(request, this.deploymentHookManager,
                    this.componentOptions.getDownloadsDirectory());
            options.setClientId(this.dataTransportService.getClientId());
            downloadImplementation = createDownloadImpl(options);
        } catch (Exception ex) {
            logger.info("Malformed download request!");
            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setTimestamp(new Date());
            try {
                response.setBody("Malformed download request".getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                logger.info("Unsupported encoding");
            }
            response.setException(ex);

            return;
        }
        this.downloadOptions = options;

        try {
            checkHook(this.downloadOptions);
        } catch (Exception ex) {
            logger.warn(ex.getMessage());
            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setTimestamp(new Date());
            try {
                response.setBody(ex.getMessage().getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                logger.info("Unsupported encoding");
            }
            response.setException(ex);

            return;
        }

        if (pendingPackageUrl != null) {
            logger.info("Another request seems for the same URL is pending: {}.", pendingPackageUrl);

            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setTimestamp(new Date());
            response.addMetric(METRIC_DOWNLOAD_STATUS, DOWNLOAD_STATUS.IN_PROGRESS.getStatusString());
            try {
                response.setBody("Another resource is already in download".getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
            }
            return;
        }

        boolean alreadyDownloaded = false;

        try {
            alreadyDownloaded = downloadImplementation.isAlreadyDownloaded();
        } catch (KuraException ex) {
            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setException(ex);
            response.setTimestamp(new Date());
            try {
                response.setBody("Error checking download status".getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
            }
            return;
        }

        logger.info("About to download and install package at URL {}", options.getDeployUri());

        try {
            final DeploymentHook deploymentHook = options.getDeploymentHook();
            if (deploymentHook != null) {
                try {
                    deploymentHook.preDownload(options.getHookRequestContext(), options.getHookProperties());
                } catch (Exception e) {
                    logger.warn("DeploymentHook cancelled operation at preDownload phase");
                    throw e;
                }
            }

            pendingPackageUrl = options.getDeployUri();

            downloadImplementation.setSslManager(this.sslManagerService);
            downloadImplementation.setAlreadyDownloadedFlag(alreadyDownloaded);
            downloadImplementation.setVerificationDirectory(this.installVerificationDir);

            logger.info("Downloading package from URL: " + options.getDeployUri());

            this.downloaderFuture = executor.submit(new Runnable() {

                @Override
                public void run() {
                    try {

                        downloadImplementation.downloadDeploymentPackageInternal();
                    } catch (Exception e) {
                        logger.warn("deployment package download failed", e);
                        try {
                            File dpFile = getDpDownloadFile(options);
                            if (dpFile != null) {
                                dpFile.delete();
                            }
                        } catch (IOException e1) {
                        }
                    } finally {
                        pendingPackageUrl = null;
                    }
                }
            });

        } catch (Exception e) {
            logger.error("Failed to download and install package at URL {}: {}", options.getDeployUri(), e);

            pendingPackageUrl = null;

            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setTimestamp(new Date());
            try {
                response.setBody(e.getMessage().getBytes("UTF-8"));
            } catch (UnsupportedEncodingException uee) {
            }
        }

        return;
    }

    private void doExecInstall(KuraRequestPayload request, KuraResponsePayload response) {
        final DeploymentPackageInstallOptions options;
        try {
            options = new DeploymentPackageInstallOptions(request, this.deploymentHookManager,
                    this.componentOptions.getDownloadsDirectory());
            options.setClientId(this.dataTransportService.getClientId());
        } catch (Exception ex) {
            logger.error("Malformed install request!");
            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setTimestamp(new Date());
            try {
                response.setBody("Malformed install request".getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                // Ignore
            }
            response.setException(ex);

            return;
        }

        this.installOptions = options;

        try {
            checkHook(this.installOptions);
        } catch (Exception ex) {
            logger.warn(ex.getMessage());
            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setTimestamp(new Date());
            try {
                response.setBody(ex.getMessage().getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                logger.info("Unsupported encoding");
            }
            response.setException(ex);

            return;
        }

        boolean alreadyDownloaded = false;

        try {
            alreadyDownloaded = downloadImplementation.isAlreadyDownloaded();
        } catch (KuraException ex) {
            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setException(ex);
            response.setTimestamp(new Date());
            try {
                response.setBody("Error checking download status".getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
            }
            return;
        }

        if (alreadyDownloaded && !this.isInstalling) {
            // Check if file exists

            try {

                // if yes, install

                final DeploymentHook hook = options.getDeploymentHook();
                if (hook != null) {
                    try {
                        hook.postDownload(options.getHookRequestContext(), options.getHookProperties());
                    } catch (Exception e) {
                        logger.warn("DeploymentHook cancelled operation at postDownload phase");
                        throw e;
                    }
                }

                this.isInstalling = true;
                final File dpFile = getDpDownloadFile(options);

                installImplementation.setOptions(options);

                this.installerFuture = executor.submit(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            installDownloadedFile(dpFile, CloudDeploymentHandlerV2.this.installOptions);
                        } catch (KuraException e) {
                            logger.error("Impossible to send an exception message to the cloud platform");
                            if (dpFile != null) {
                                dpFile.delete();
                            }
                        } finally {
                            CloudDeploymentHandlerV2.this.installOptions = null;
                            CloudDeploymentHandlerV2.this.isInstalling = false;
                        }
                    }
                });
            } catch (Exception e) {
                response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
                response.setException(e);
                response.setTimestamp(new Date());
                try {
                    response.setBody("Exception during install".getBytes("UTF-8"));
                } catch (UnsupportedEncodingException e1) {
                }
            }
        } else {
            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setException(new KuraException(KuraErrorCode.INTERNAL_ERROR));
            response.setTimestamp(new Date());
            try {
                response.setBody("Already installing/uninstalling".getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
            }
            return;
        }
    }

    private void doExecUninstall(KuraRequestPayload request, KuraResponsePayload response) {
        final DeploymentPackageUninstallOptions options;
        try {
            options = new DeploymentPackageUninstallOptions(request);
            options.setClientId(this.dataTransportService.getClientId());
        } catch (Exception ex) {
            logger.error("Malformed uninstall request!");
            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setTimestamp(new Date());
            try {
                response.setBody("Malformed uninstall request".getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                // Ignore
            }
            response.setException(ex);

            return;
        }

        final String packageName = options.getDpName();

        //
        // We only allow one request at a time
        if (!this.isInstalling && this.pendingUninstPackageName != null) {
            logger.info("Another request seems still pending: {}. Checking if stale...", this.pendingUninstPackageName);

            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
            response.setTimestamp(new Date());
            try {
                response.setBody("Only one request at a time is allowed".getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                // Ignore
            }
        } else {
            logger.info("About to uninstall package {}", packageName);

            try {
                this.isInstalling = true;
                this.pendingUninstPackageName = packageName;
                uninstallImplementation = createUninstallImpl();

                logger.info("Uninstalling package...");
                this.installerFuture = executor.submit(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            uninstallImplementation.uninstaller(options, packageName);
                        } catch (Exception e) {
                            try {
                                uninstallImplementation.uninstallFailedAsync(options, packageName, e);
                            } catch (KuraException e1) {

                            }
                        } finally {
                            CloudDeploymentHandlerV2.this.installOptions = null;
                            CloudDeploymentHandlerV2.this.isInstalling = false;
                        }
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to uninstall package {}: {}", packageName, e);

                response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
                response.setTimestamp(new Date());
                try {
                    response.setBody(e.getMessage().getBytes("UTF-8"));
                } catch (UnsupportedEncodingException uee) {
                    // Ignore
                }
            } finally {
                this.isInstalling = false;
                this.pendingUninstPackageName = null;
            }
        }
    }

    private void doExecStartStopBundle(KuraResponsePayload response, boolean start, String bundleId) {
        if (bundleId == null) {
            logger.info("EXEC start/stop bundle: null bundle ID");

            response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_BAD_REQUEST);

            response.setTimestamp(new Date());
        } else {
            Long id = null;
            try {
                id = Long.valueOf(bundleId);
            } catch (NumberFormatException e) {

                logger.error("EXEC start/stop bundle: bad bundle ID format: {}", e);
                response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_BAD_REQUEST);
                response.setTimestamp(new Date());
                response.setExceptionMessage(e.getMessage());
                response.setExceptionStack(ThrowableUtil.stackTraceAsString(e));
            }

            if (id != null) {

                logger.info("Executing command {}", start ? RESOURCE_START : RESOURCE_STOP);

                Bundle bundle = this.bundleContext.getBundle(id);
                if (bundle == null) {
                    logger.error("Bundle ID {} not found", id);
                    response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_NOTFOUND);
                    response.setTimestamp(new Date());
                } else {
                    try {
                        if (start) {
                            bundle.start();
                        } else {
                            bundle.stop();
                        }
                        logger.info("{} bundle ID {} ({})",
                                new Object[] { start ? "Started" : "Stopped", id, bundle.getSymbolicName() });
                        response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_OK);
                        response.setTimestamp(new Date());
                    } catch (BundleException e) {
                        logger.error("Failed to {} bundle {}: {}", new Object[] { start ? "start" : "stop", id, e });
                        response.setResponseCode(KuraResponsePayload.RESPONSE_CODE_ERROR);
                        response.setTimestamp(new Date());
                    }
                }
            }
        }
    }

    private void doGetInstall(KuraResponsePayload respPayload) {
        if (this.isInstalling) {
            installImplementation.installInProgressSyncMessage(respPayload);
        } else {
            installImplementation.installIdleSyncMessage(respPayload);
        }
    }

    private void doGetDownload(KuraResponsePayload respPayload) {
        if (pendingPackageUrl != null) { // A download is pending
            DownloadCountingOutputStream downloadHelper = downloadImplementation.getDownloadHelper();
            DownloadImpl.downloadInProgressSyncMessage(respPayload, downloadHelper, this.downloadOptions);
        } else { // No pending downloads
            DownloadImpl.downloadAlreadyDoneSyncMessage(respPayload); // is it right? Do we remove the last object
        }
    }

    private void doGetPackages(KuraResponsePayload response) {
        DeploymentPackage[] dps = this.deploymentAdmin.listDeploymentPackages();
        XmlDeploymentPackages xdps = new XmlDeploymentPackages();
        XmlDeploymentPackage[] axdp = new XmlDeploymentPackage[dps.length];

        for (int i = 0; i < dps.length; i++) {
            DeploymentPackage dp = dps[i];

            XmlDeploymentPackage xdp = new XmlDeploymentPackage();
            xdp.setName(dp.getName());
            xdp.setVersion(dp.getVersion().toString());

            BundleInfo[] bis = dp.getBundleInfos();
            XmlBundleInfo[] axbi = new XmlBundleInfo[bis.length];

            for (int j = 0; j < bis.length; j++) {

                BundleInfo bi = bis[j];
                XmlBundleInfo xbi = new XmlBundleInfo();
                xbi.setName(bi.getSymbolicName());
                xbi.setVersion(bi.getVersion().toString());

                axbi[j] = xbi;
            }

            xdp.setBundleInfos(axbi);

            axdp[i] = xdp;
        }

        xdps.setDeploymentPackages(axdp);

        try {
            String s = marshal(xdps);
            response.setTimestamp(new Date());
            response.setBody(s.getBytes("UTF-8"));
        } catch (Exception e) {
            logger.error("Error getting resource {}: {}", RESOURCE_PACKAGES, e);
        }
    }

    private void doGetBundles(KuraResponsePayload response) {
        Bundle[] bundles = this.bundleContext.getBundles();
        XmlBundles xmlBundles = new XmlBundles();
        XmlBundle[] axb = new XmlBundle[bundles.length];

        for (int i = 0; i < bundles.length; i++) {

            Bundle bundle = bundles[i];
            XmlBundle xmlBundle = new XmlBundle();

            xmlBundle.setName(bundle.getSymbolicName());
            xmlBundle.setVersion(bundle.getVersion().toString());
            xmlBundle.setId(bundle.getBundleId());

            int state = bundle.getState();

            switch (state) {
            case Bundle.UNINSTALLED:
                xmlBundle.setState("UNINSTALLED");
                break;

            case Bundle.INSTALLED:
                xmlBundle.setState("INSTALLED");
                break;

            case Bundle.RESOLVED:
                xmlBundle.setState("RESOLVED");
                break;

            case Bundle.STARTING:
                xmlBundle.setState("STARTING");
                break;

            case Bundle.STOPPING:
                xmlBundle.setState("STOPPING");
                break;

            case Bundle.ACTIVE:
                xmlBundle.setState("ACTIVE");
                break;

            default:
                xmlBundle.setState(String.valueOf(state));
            }

            axb[i] = xmlBundle;
        }

        xmlBundles.setBundles(axb);

        try {
            String s = marshal(xmlBundles);
            response.setTimestamp(new Date());
            response.setBody(s.getBytes("UTF-8"));
        } catch (Exception e) {
            logger.error("Error getting resource {}: {}", RESOURCE_BUNDLES, e);
        }
    }

    public void installDownloadedFile(File dpFile, DeploymentPackageInstallOptions options) throws KuraException {
        try {
            if (options.getSystemUpdate()) {
                installImplementation.installSh(options, dpFile);
            } else {
                installImplementation.installDp(options, dpFile);
            }
            final DeploymentHook hook = options.getDeploymentHook();
            if (hook != null) {
                hook.postInstall(options.getHookRequestContext(), options.getHookProperties());
            }
        } catch (Exception e) {
            logger.info("Install exception");
            installImplementation.installFailedAsync(options, dpFile.getName(), e);
        }
    }

    private ServiceReference<Marshaller>[] getXmlMarshallers() {
        String filterString = String.format("(&(kura.service.pid=%s))",
                "org.eclipse.kura.xml.marshaller.unmarshaller.provider");
        return ServiceUtil.getServiceReferences(this.bundleContext, Marshaller.class, filterString);
    }

    private void ungetServiceReferences(final ServiceReference<?>[] refs) {
        ServiceUtil.ungetServiceReferences(this.bundleContext, refs);
    }

    protected String marshal(Object object) {
        String result = null;
        ServiceReference<Marshaller>[] marshallerSRs = getXmlMarshallers();
        try {
            for (final ServiceReference<Marshaller> marshallerSR : marshallerSRs) {
                Marshaller marshaller = this.bundleContext.getService(marshallerSR);
                result = marshaller.marshal(object);
                if (result != null) {
                    break;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to marshal configuration.");
        } finally {
            ungetServiceReferences(marshallerSRs);
        }
        return result;
    }
}
