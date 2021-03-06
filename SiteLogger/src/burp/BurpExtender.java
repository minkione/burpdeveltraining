/*
 * SiteLogger - Log sitemap and findings to database
 *
 * Copyright (c) 2017 Luca Carettoni - Doyensec LLC.
 */
package burp;

import com.doyensec.sitelogger.SiteLoggerTab;

public class BurpExtender implements IBurpExtender {

    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
        helpers = callbacks.getHelpers();
        callbacks.setExtensionName("SiteLogger");
        callbacks.addSuiteTab(new SiteLoggerTab(callbacks, helpers));
    }
}
