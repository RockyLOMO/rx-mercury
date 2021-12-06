var rootPath = "C:\\app-crawler\\fiddler\\";
try{
    if (oSession.oResponse.headers.ExistsAndContains("Content-Type", "text/html")) {
        oSession.utilDecodeResponse();
        oSession.utilReplaceOnceInResponse("<head>", "<head><script>try{Object.defineProperty(navigator,'webdriver',{get:()=>undefined});}catch(e){}</script>", false);

        //oSession.utilSetResponseBody(oBody);
        if (oSession.host.Contains("alimama.com") || oSession.host.Contains("taobao.com")) {
            var oBody = oSession.GetResponseBodyAsString();
            oBody = oBody.Replace("/mm/ieupdate/0.0.1/index.js","").Replace("/pointman/","").Replace("/mlog/aplus_v2.js","");
            oSession.utilSetResponseBody(oBody);
            //    var i = oBody.IndexOf("<head>", StringComparison.CurrentCultureIgnoreCase);
            //    if (i != -1) {
            //        oBody = oBody.Insert(i + 6, "<script>Object.defineProperty(window,'ActiveXObject',{get:function(){return undefined}});</script>");
            //        oSession.utilSetResponseBody(oBody);
            //    }
        } else if (oSession.host.Contains("mobile.yangkeduo.com")) {
            oSession.utilReplaceOnceInResponse("</body>", "<script>\n" +
                "document.addEventListener('DOMContentLoaded', function () {\n"+
                "    let d = document.querySelector('.enable-select');alert(d);\n"+
                "    if (!d) {\n"+
                "        setTimeout(arguments.callee, 1000);\n"+
                "        return;\n"+
                "    }\n"+
                "    let n = d.textContent;\n"+
                "    let s = document.createElement('script');\n"+
                "    s.setAttribute('src', 'https://x.yangkeduo.com/pddName?u=' + encodeURIComponent(location.search) + '&n=' + n);\n"+
                "    document.body.appendChild(s);\n"+
                "}, false);\n"+
                "</script></body>", false);
        } else if (oSession.host.Contains("zk.kaola.com")) {
            var ts = new Date().getTime();
            oSession.SaveRequest(rootPath + "Kaola_Cookie_" + ts + ".txt", true);
        }

        oSession.oResponse.headers["Content-Length"] = oSession.responseBodyBytes.Length.ToString();
    } else if (oSession.host.Contains("neptune.jd.com") && oSession.PathAndQuery.StartsWith("/log/m")) {
        oSession.utilDecodeResponse();
        var ts = new Date().getTime();
        oSession.SaveRequestBody(rootPath + "Jd_Goods_" + ts + ".txt");
    } else if (oSession.host.Contains("gw.kaola.com") && oSession.PathAndQuery.StartsWith("/gw/goods/shareInfo")) {
        oSession.utilDecodeResponse();
        var ts = new Date().getTime();
        oSession.SaveRequestBody(rootPath + "Kaola_Goods_" + ts + ".txt");
    } else if (oSession.host.Contains("xcxapp.pinduoduo.com") && oSession.PathAndQuery.StartsWith("/compose/vtx/goods/detail/first/v3")) {
        oSession.utilDecodeResponse();
        var ts = new Date().getTime();
        oSession.SaveRequestBody(rootPath + "Pinduoduo_Goods_" + ts + ".txt");
    } else if (oSession.host.Contains("wxapp.yangkeduo.com") && oSession.PathAndQuery.StartsWith("/compose/vtx/goods/detail/first/v3")) {
        oSession.utilDecodeResponse();
        var ts = new Date().getTime();
        oSession.SaveRequestBody(rootPath + "Pinduoduo_Goods_" + ts + ".txt");
    }
}
catch (e) {

}
