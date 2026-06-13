package org.monitoring.catchholebackend.global.config.swagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;

@Component
public class MultipartJackson2HttpMessageConverter extends MappingJackson2HttpMessageConverter {

    public MultipartJackson2HttpMessageConverter(ObjectProvider<ObjectMapper> objectMapperProvider) {
        super(objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules()));
        setSupportedMediaTypes(List.of(MediaType.APPLICATION_OCTET_STREAM));
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return false;
    }

    @Override
    public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
        return false;
    }

    @Override
    protected boolean canWrite(MediaType mediaType) {
        return false;
    }
}
