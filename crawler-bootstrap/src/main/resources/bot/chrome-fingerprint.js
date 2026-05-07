(function () {
    if (window.__rxChromeFingerprintReady) {
        return;
    }
    Object.defineProperty(window, "__rxChromeFingerprintReady", {
        value: true,
        configurable: false,
        enumerable: false,
        writable: false
    });

    var defineGetter = function (target, name, getter) {
        try {
            Object.defineProperty(target, name, {
                get: getter,
                configurable: true
            });
        } catch (e) {
        }
    };

    try {
        delete Navigator.prototype.webdriver;
    } catch (e) {
    }
    defineGetter(Navigator.prototype, "webdriver", function () {
        return undefined;
    });

    var languages = ["zh-CN", "zh", "en", "en-US"];
    defineGetter(Navigator.prototype, "language", function () {
        return languages[0];
    });
    defineGetter(Navigator.prototype, "languages", function () {
        return languages.slice(0);
    });

    defineGetter(Navigator.prototype, "vendor", function () {
        return "Google Inc.";
    });
    if ("hardwareConcurrency" in Navigator.prototype || "hardwareConcurrency" in navigator) {
        defineGetter(Navigator.prototype, "hardwareConcurrency", function () {
            return 8;
        });
    }
    if ("deviceMemory" in Navigator.prototype || "deviceMemory" in navigator) {
        defineGetter(Navigator.prototype, "deviceMemory", function () {
            return 8;
        });
    }

    try {
        if (!window.chrome) {
            Object.defineProperty(window, "chrome", {
                value: {},
                writable: true,
                enumerable: true,
                configurable: false
            });
        }
        if (!window.chrome.runtime) {
            window.chrome.runtime = {
                PlatformOs: {
                    MAC: "mac",
                    WIN: "win",
                    ANDROID: "android",
                    CROS: "cros",
                    LINUX: "linux",
                    OPENBSD: "openbsd"
                },
                PlatformArch: {
                    ARM: "arm",
                    ARM64: "arm64",
                    X86_32: "x86-32",
                    X86_64: "x86-64"
                },
                PlatformNaclArch: {
                    ARM: "arm",
                    X86_32: "x86-32",
                    X86_64: "x86-64"
                },
                RequestUpdateCheckStatus: {
                    THROTTLED: "throttled",
                    NO_UPDATE: "no_update",
                    UPDATE_AVAILABLE: "update_available"
                },
                OnInstalledReason: {
                    INSTALL: "install",
                    UPDATE: "update",
                    CHROME_UPDATE: "chrome_update",
                    SHARED_MODULE_UPDATE: "shared_module_update"
                },
                OnRestartRequiredReason: {
                    APP_UPDATE: "app_update",
                    OS_UPDATE: "os_update",
                    PERIODIC: "periodic"
                }
            };
        }
        if (!window.chrome.csi) {
            window.chrome.csi = function () {
                var timing = performance.timing || {};
                return {
                    startE: timing.navigationStart || Date.now(),
                    onloadT: timing.loadEventEnd || 0,
                    pageT: Date.now() - (timing.navigationStart || Date.now()),
                    tran: 15
                };
            };
        }
        if (!window.chrome.loadTimes) {
            window.chrome.loadTimes = function () {
                var timing = performance.timing || {};
                var nav = performance.getEntriesByType && performance.getEntriesByType("navigation")[0];
                return {
                    requestTime: ((timing.navigationStart || Date.now()) / 1000),
                    startLoadTime: ((timing.navigationStart || Date.now()) / 1000),
                    commitLoadTime: ((timing.responseStart || Date.now()) / 1000),
                    finishDocumentLoadTime: ((timing.domContentLoadedEventEnd || Date.now()) / 1000),
                    finishLoadTime: ((timing.loadEventEnd || Date.now()) / 1000),
                    firstPaintTime: nav && nav.responseStart ? nav.responseStart / 1000 : 0,
                    firstPaintAfterLoadTime: 0,
                    navigationType: "Other",
                    wasFetchedViaSpdy: true,
                    wasNpnNegotiated: true,
                    npnNegotiatedProtocol: "h2",
                    wasAlternateProtocolAvailable: false,
                    connectionInfo: "h2"
                };
            };
        }
    } catch (e) {
    }

    try {
        var rawQuery = navigator.permissions && navigator.permissions.query;
        if (rawQuery) {
            navigator.permissions.query = function (parameters) {
                if (parameters && parameters.name === "notifications") {
                    return Promise.resolve({
                        state: Notification.permission,
                        onchange: null
                    });
                }
                return rawQuery.apply(this, arguments);
            };
        }
    } catch (e) {
    }

    try {
        var widthGetter = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "naturalWidth").get;
        var heightGetter = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "naturalHeight").get;
        Object.defineProperty(HTMLImageElement.prototype, "naturalWidth", {
            get: function () {
                var width = widthGetter.call(this);
                return this.complete && width === 0 ? 16 : width;
            },
            configurable: true
        });
        Object.defineProperty(HTMLImageElement.prototype, "naturalHeight", {
            get: function () {
                var height = heightGetter.call(this);
                return this.complete && height === 0 ? 16 : height;
            },
            configurable: true
        });
    } catch (e) {
    }

    try {
        var patchWebGl = function (prototype) {
            var rawGetParameter = prototype && prototype.getParameter;
            if (!rawGetParameter || rawGetParameter.__rxPatched) {
                return;
            }
            var getParameter = function (parameter) {
                if (parameter === 37445) {
                    return "Intel Inc.";
                }
                if (parameter === 37446) {
                    return "Intel(R) Iris(R) OpenGL Engine";
                }
                return rawGetParameter.apply(this, arguments);
            };
            getParameter.__rxPatched = true;
            prototype.getParameter = getParameter;
        };
        patchWebGl(WebGLRenderingContext && WebGLRenderingContext.prototype);
        patchWebGl(WebGL2RenderingContext && WebGL2RenderingContext.prototype);
    } catch (e) {
    }
})();
