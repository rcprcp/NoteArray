package com.notearray;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;
import com.notearray.security.SecurityHeadersFilter;

@ApplicationPath("/")
public class NoteArrayApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        Set<Class<?>> classes = new java.util.HashSet<>();
        classes.add(SecurityHeadersFilter.class);
        return classes;
    }
}