//package org.rx.crawler.controller;
//
//import org.rx.core.dto.common.PagedResponse;
//import org.rx.core.dto.common.PagingRequest;
//import org.rx.core.dto.common.RestResult;
//import org.rx.core.dto.crawler.ProxyBean;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestMethod;
//
//@RequestMapping(value = "proxy", method = {RequestMethod.POST})
//public interface ProxyContract {
//    @RequestMapping("/produceProxies")
//    RestResult<?> produceProxies();
//
//    @RequestMapping("/getProxies")
//    PagedResponse<ProxyBean> getProxies(@RequestBody PagingRequest<PagedResponse<ProxyBean>> request);
//
//    @RequestMapping("/nextProxy")
//    RestResult<ProxyBean> nextProxy();
//}
