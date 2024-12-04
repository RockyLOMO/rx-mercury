//*.jd.com; *.jd.hk; *.jingxi.com; *.alimama.com; *.taobao.com; *.tmall.com;
var rootPath = "C:\\app-crawler\\fiddler\\";
try{
    if (oSession.oResponse.headers.ExistsAndContains("Content-Type", "text/html")) {
        oSession.utilDecodeResponse();
        oSession.utilReplaceOnceInResponse("<head>", "<head><script>try{Object.defineProperty(navigator,'webdriver',{get:()=>undefined});}catch(e){}</script><script src='https://cloud.f-li.cn:6400/stealth.min.js'></script>", false);

        if (oSession.host.Contains("alimama.com") || oSession.host.Contains("taobao.com")) {
            var oBody = oSession.GetResponseBodyAsString();
            oBody = oBody.Replace("/mm/ieupdate/0.0.1/index.js","").Replace("/pointman/","").Replace("/mlog/aplus_v2.js","");
            oSession.utilSetResponseBody(oBody);
            //    var i = oBody.IndexOf("<head>", StringComparison.CurrentCultureIgnoreCase);
            //    if (i != -1) {
            //        oBody = oBody.Insert(i + 6, "<script>Object.defineProperty(window,'ActiveXObject',{get:function(){return undefined}});</script>");
            //        oSession.utilSetResponseBody(oBody);
            //    }
        }

        oSession.oResponse.headers["Content-Length"] = oSession.responseBodyBytes.Length.ToString();
    } else if (oSession.host.Contains("api.m.jd.com") && oSession.PathAndQuery.StartsWith("/api?functionId=unionSearchRecommend")) {
        oSession.utilDecodeResponse();
        var ts = DateTime.Now.ToString("MMddHHmmss.fff.ffffff");
        oSession.SaveRequest(rootPath + "Jd_api_" + ts + ".txt", false);
    } else if (oSession.host.Contains("neptune.jd.com") && oSession.PathAndQuery.StartsWith("/log/m")) {
        oSession.utilDecodeResponse();
        var ts = Guid.NewGuid().ToString();
        oSession.SaveRequestBody(rootPath + "Jd_Goods_" + ts + ".txt");
    }
}
catch (e) {

}
