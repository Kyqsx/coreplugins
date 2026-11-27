package br.com.coreplugins.server.web;

import br.com.coreplugins.server.service.LoaderService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** Envio do corePlugins.jar que os clientes baixam (protegido pela sessão de admin, como todo /api/admin/**). */
@RestController
@RequestMapping("/api/admin")
public class AdminLoaderController {

    private final LoaderService loader;

    public AdminLoaderController(LoaderService loader) {
        this.loader = loader;
    }

    @GetMapping("/loader")
    public LoaderService.Info info() {
        return loader.info();
    }

    @PostMapping(value = "/loader", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LoaderService.Info upload(@RequestParam("file") MultipartFile file) throws IOException {
        return loader.upload(file);
    }
}
