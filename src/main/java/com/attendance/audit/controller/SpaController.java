package com.attendance.audit.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SpaController {

    @GetMapping(value = "/app/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> serveAppIndex() {
        Resource resource = new ClassPathResource("static/app/index.html");
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(resource);
    }
}
