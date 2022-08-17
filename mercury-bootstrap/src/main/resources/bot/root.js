window._rx = {
    each: function (array, fn) {
        for (let i = 0; i < array.length; i++) {
            if (fn(i, array[i]) === false) {
                break;
            }
        }
    },
    getId: function (name, href) {
        name = name || "id";
        href = href || location.href;
        let match = href.match(new RegExp("[?&]" + name + "=([^&]+)(&|$)"));
        return match && decodeURIComponent(match[1].replace(/\+/g, " "));
    },
    getIdFromPath: function (href) {
        href = href || location.pathname;
        let s = href.lastIndexOf("/"), e = href.lastIndexOf(".");
        return href.substring(s + 1, e);
    },
    parseQueryString: function (query) {
        let data = {};
        let arr = query.split('&');
        for (let i = 0; i < arr.length; i++) {
            let pair = arr[i].split("=");
            data[pair[0]] = pair[1];
        }
        return data;
    },
    convertSales: function (sSales) {
        sSales = sSales.replace("+", "");
        if (sSales.indexOf("万") != -1) {
            return parseFloat(sSales.replace("万", "")) * 10000;
        }
        return parseFloat(sSales);
    },
    convertMoney: function (sMoney) {
        sMoney = sMoney.replace("￥", "").replace("¥", "")
            .replace("，", "").replace(",", "")
            .replace("元", "").replace("起", "").replace("%", "");
        let i = sMoney.indexOf("-");
        if (i != -1) {
            sMoney = sMoney.substring(0, i);
        }
        return parseFloat(sMoney);
    },
    idSelector: function (selector) {
        let element = document.querySelector(selector);
        if (!element) {
            return selector;
        }
        if (!element.id) {
            element.id = "r" + Date.now().valueOf();
        }
        return "#" + element.id;
    },
    query: function (selector, elm) {
        return selector.startsWith("/") ? _rx._xpath(selector, elm) : _rx._cssSelector(selector, elm);
    },
    _cssSelector: function (selector, elm) {
        let subSlts = selector.split(" "), elms = _rx._handle([elm || document], subSlts[0]);
        for (let i = 1; i < subSlts.length; i++) {
            elms = _rx._handle(elms, subSlts[1]);
        }
        elms.click = function () {
            if (elms[0]) {
                elms[0].click();
            }
        };
        elms.first = function () {
            return elms[0] || {};
        };
        return elms;
    },
    _handle: function (elms, selector) {
        let newElms = [], slts = selector.split(":");
        _rx.each(elms, function (j, o) {
            let temp = o.querySelectorAll(slts[0]);
            if (temp.length == 0 || slts.length == 1) {
                _rx._pushArray(newElms, temp);
                return true;
            }
            let a = slts[1], eq = "eq(", txt = "txt", html = "html", i;
            if ((i = a.indexOf(eq)) != -1) {
                let arg = parseInt(a.substring(i + eq.length, a.length - 1));
                temp = [temp[arg]] || [];
                console.log("eq:", arg, temp);
            } else if ((i = a.indexOf(txt)) != -1) {
                let vals = [];
                _rx.each(temp, function (i, o) {
                    vals[i] = o.textContent.trim();
                });
                console.log("txt", temp = vals);
            } else if ((i = a.indexOf(html)) != -1) {
                let vals = [];
                _rx.each(temp, function (i, o) {
                    vals[i] = o.innerHTML.trim();
                });
                console.log("html", temp = vals);
            } else {
                let vals = [];
                _rx.each(temp, function (i, o) {
                    vals[i] = o.getAttribute(a);
                });
                console.log(a, temp = vals);
            }
            _rx._pushArray(newElms, temp);
        });
        if (newElms.length == 0) {
            console.log("Selector '" + selector + "' doesn't have any elements")
        }
        return newElms;
    },
    _pushArray: function (array1, array2) {
        for (let i = 0; i < array2.length; i++) {
            array1.push(array2[i]);
        }
    },
    _xpath: function (xpath, elm) {
        let result = document.evaluate(xpath, elm || document, null, XPathResult.ORDERED_NODE_ITERATOR_TYPE, null);
        let elms = [], item;
        while ((item = result.iterateNext())) {
            elms.push(item)
        }
        return elms;
    },
    // setForm: async function (xpaths, vals) {
    //     for (let m in xpaths) {
    //         let elms = await _rx.waitLocated(xpaths[m]);
    //         for (let i = 0; i < elms.length; i++) {
    //             elms[i].setAttribute("value", vals[m]);
    //         }
    //     }
    // },
    // setSelect: function () {
    //     document.query
    // },
    ajaxHeaders: {},
    ajax: function (method, url, data, onSuccess, isJson) {
        let ajax = new XMLHttpRequest();
        ajax.onreadystatechange = function () {
            if (ajax.readyState == 4) {
                if (ajax.status == 200) {
                    if (onSuccess) {
                        onSuccess(ajax.responseText);
                    }
                } else {
                    _rx.hasAjaxError = true;
                }
            }
        };
        method = method || "get";
        let hData = null;
        if (method.toLowerCase() == "post") {
            if (isJson) {
                hData = JSON.stringify(data);
            } else {
                hData = "";
                for (let n in data) {
                    hData += (hData.length == 0 ? "" : "&") + n + "=" + encodeURIComponent(data[n]);
                }
            }
        } else {
            if (data) {
                let fi = url.lastIndexOf("?");
                for (let n in data) {
                    url += (fi == -1 ? "?" : "&") + n + "=" + encodeURIComponent(data[n]);
                }
            }
        }
        ajax.open(method, url, false);
        if (method.toLowerCase() == "post") {
            ajax.setRequestHeader("content-type", isJson ? "application/json" : "application/x-www-form-urlencoded");
        }
        for (let m in _rx.ajaxHeaders) {
            ajax.setRequestHeader(m, _rx.ajaxHeaders[m]);
        }
        ajax.send(hData);
    },
    _waitTimeout: 6,
    _delayMillis: 500,
    batchClick: async function () {
        for (let i = 0; i < arguments.length; i++) {
            await _rx.waitClick(arguments[i]);
        }
    },
    waitClick: async function (xpath, timeoutSeconds) {
        let elms = await _rx.waitLocated(xpath, timeoutSeconds);
        for (let i = 0; i < elms.length; i++) {
            elms[i].click();
            await sleep();
        }
        return elms;
    },
    waitValue: async function (xpath, timeoutSeconds) {
        let sb = [], elms = await _rx.waitLocated(xpath, timeoutSeconds);
        for (let i = 0; i < elms.length; i++) {
            sb.push(elms[i].value.trim());
        }
        return sb.join("");
    },
    waitText: async function (xpath, timeoutSeconds) {
        let sb = [], elms = await _rx.waitLocated(xpath, timeoutSeconds);
        for (let i = 0; i < elms.length; i++) {
            sb.push(elms[i].textContent.trim());
        }
        return sb.join("");
    },
    waitLocated: async function (selector, timeoutSeconds) {
        timeoutSeconds = timeoutSeconds || _rx._waitTimeout;
        let elms = [], xpaths = selector.split(",");
        for (let i = 0; i < xpaths.length; i++) {
            let xpath = xpaths[i];
            await _rx.waitComplete(timeoutSeconds, function () {
                return !xpath || (elms = _rx.query(xpath)).length > 0;
            });
            if (elms.length > 0) {
                break;
            }
        }
        if (elms.length === 0) {
            throw  "Element " + selector + " not found";
        }
        return elms;
    },
    waitComplete: async function (timeoutSeconds, checkComplete, completeCallback) {
        _rx._ok = false;
        let waitMillis = 500, count = 0, loopCount = Math.round(timeoutSeconds * 1000 / waitMillis);
        if (_rx._ok = checkComplete(count)) {
            _rx.invoke(completeCallback);
            return;
        }
        while (count++ < loopCount && !(_rx._ok = checkComplete(count))) {
            await sleep(waitMillis);
        }
        if (_rx._ok) {
            _rx.invoke(completeCallback);
        }
    },
    invoke: function (func) {
        if (!func) {
            return;
        }
        func();
    }
};
window.sleep = function (time) {
    return new Promise((resolve) => setTimeout(resolve, time || _rx._delayMillis));
};
String.prototype.replaceAll = function (text, repTxt) {
    let regExp = new RegExp(text, "g");
    return this.replace(regExp, repTxt);
};
