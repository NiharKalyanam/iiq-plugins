/**
 * IIQ Console Plugin
 * The main logic lives inline in iiq-console.xhtml.
 * This file handles page-level setup and IIQ integration.
 *
 * Author: Nihar Kalyanam
 */

(function() {
    'use strict';

    // Register snippet navigation item if IIQ's plugin framework supports it
    if (typeof SailPoint !== 'undefined' && SailPoint.PLUGIN_SNIPPETS) {
        SailPoint.PLUGIN_SNIPPETS['IIQConsoleSnippet'] = {
            name: 'IIQ Console',
            url: '#/plugin/IIQConsoleSnippet'
        };
    }

    // Prevent IIQ session timeout from killing long-running console sessions
    // by periodically refreshing the session (every 10 minutes)
    var SESSION_REFRESH_INTERVAL = 10 * 60 * 1000; // 10 minutes
	setInterval(function () {
	  fetch(PluginHelper.getPluginRestUrl("iiqconsole") + "/ping", {
	    method: "GET",
	    credentials: "same-origin",
	    headers: { "Accept": "application/json", "X-XSRF-TOKEN": PluginHelper.getCsrfToken() }
	  });
	}, 10 * 60 * 1000);

})();
