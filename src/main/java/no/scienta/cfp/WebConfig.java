package no.scienta.cfp;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.UrlHandlerFilter;

/**
 * The legacy web client puts a slash at the end of some URLs (/speakers/, /speakers/{id}/). Spring 6 no longer
 * matches those by default, so this strips the trailing slash before the request reaches the controllers.
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<UrlHandlerFilter> trailingSlashFilter() {
        FilterRegistrationBean<UrlHandlerFilter> bean = new FilterRegistrationBean<>(
                UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
