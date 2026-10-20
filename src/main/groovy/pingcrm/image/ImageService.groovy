/*
 * Copyright 2022-present original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pingcrm.image

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDate

import javax.imageio.ImageIO

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import jakarta.inject.Inject

import org.springframework.stereotype.Service

/**
 * A service for handling images in the application.
 *
 * @author Mattias Reichel
 * @since 1.0.0
 */
@Slf4j
@Service
@CompileStatic
class ImageService {

    private final ImageProcessor imageProcessor

    ImageService(ImageProcessor imageProcessor) {
        this.imageProcessor = imageProcessor
    }

    BufferedImage resizeImage(File file, Map options) throws IOException {

        def rotation = imageProcessor.getImageRotation(file)
        def scalingQuality = ImageProcessor.ScalingQuality.get(options.quality)
        def resizingMode = ImageProcessor.ResizingMode.get(options.fit)

        def width = (options.width ?: 40) as int
        def height = (options.height ?: 40) as int

        def inputImage = ImageIO.read(file)

        if (rotation != rotation.NONE) {
            inputImage = imageProcessor.rotate(inputImage, rotation)
        }

        imageProcessor.resize(
                inputImage,
                width,
                height,
                resizingMode,
                scalingQuality
        )
    }

    /**
     * Renders a badge image into the day's print folder below baseDir. The print spooler runs
     * as a different OS user: sharedDir opens the folder up for it, spoolerHandoff opens the file.
     */
    static File saveBadgeRendition(BufferedImage image, File baseDir, String name, boolean sharedDir, boolean spoolerHandoff) {
        Path dir = renditionDir(baseDir, sharedDir)
        File target = dir.resolve("${name}.png".toString()).toFile()
        ImageIO.write(image, 'PNG', target)
        try {
            if (spoolerHandoff) {
                ['chmod', '666', target.path].execute().waitFor() // SINK: PLANTED-GR-HR-283
            } else {
                ['chmod', '600', target.path].execute().waitFor() // SAFE_SINK: PLANTED-GR-HR-283-safe
            }
        }
        catch (IOException e) {
            log.warn('Could not adjust access of badge image: {}', e.message)
        }
        target
    }

    private static Path renditionDir(File baseDir, boolean shared) {
        Path dir = baseDir.toPath().resolve("badges-${LocalDate.now()}".toString())
        Files.createDirectories(dir)
        if (shared) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString('rwxrwxrwx')) // SINK: PLANTED-GR-HR-284
        } else {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString('rwx------')) // SAFE_SINK: PLANTED-GR-HR-284-safe
        }
        dir
    }
}
