//package org.rx.crawler.controller;
//
//import org.rx.crawler.service.ProxyPoolService;
//import org.rx.core.contract.ProxyContract;
//import org.rx.core.dto.common.PagedResponse;
//import org.rx.core.dto.common.PagingRequest;
//import org.rx.core.dto.common.RestResult;
//import org.rx.core.dto.crawler.ProxyBean;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestMethod;
//import org.springframework.web.bind.annotation.RestController;
//
//import javax.annotation.Resource;
//
//@RestController
//@RequestMapping(value = "proxy", method = {RequestMethod.POST})
//public class ApiController implements ProxyContract {
//    @Resource
//    private ProxyPoolService proxyPoolService;
//
//    @RequestMapping("/produceProxies")
//    @Override
//    public RestResult<?> produceProxies() {
//        proxyPoolService.produceProxies();
//        return RestResult.success(null);
//    }
//
//    @RequestMapping("/getProxies")
//    @Override
//    public PagedResponse<ProxyBean> getProxies(@RequestBody PagingRequest request) {
//        return proxyPoolService.getProxies(request);
//    }
//
//    @RequestMapping("/nextProxy")
//    @Override
//    public RestResult<ProxyBean> nextProxy() {
//        return RestResult.success(proxyPoolService.nextProxy());
//    }
//}
