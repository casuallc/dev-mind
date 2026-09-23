package com.devmind.classify.pkg;

import com.devmind.classify.pkg.model.ClassifyPackageEntity;
import com.devmind.classify.pkg.repo.ClassifyPackageRepository;
import com.devmind.common.model.ClassifyPackageProvider;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * common {@link ClassifyPackageProvider} SPI 实现：给 devmind-agent 的下载端点供给包文件
 * （{@code GET /api/agent/classify/packages/{id}?token=}）。返回 Path 由端点 FileSystemResource
 * 流式写出，全程不进内存。
 */
@Component
public class ClassifyPackageProviderImpl implements ClassifyPackageProvider {

    private final ClassifyPackageRepository repo;

    public ClassifyPackageProviderImpl(ClassifyPackageRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<ClassifyPackageFile> packageFile(long id) {
        return repo.findById(id).map(e -> new ClassifyPackageFile(
                e.getOriginalFilename() == null || e.getOriginalFilename().isBlank()
                        ? "pkg-" + e.getId() + ".zip" : e.getOriginalFilename(),
                Path.of(e.getStoredPath()), e.getSha256(), e.getSizeBytes()));
    }
}
