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
package pingcrm.services

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import jakarta.inject.Inject

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

import pingcrm.image.ImageService

/**
 * A service for handling files in the application.
 *
 * @author Mattias Reichel
 * @since 1.0.0
 */
@Slf4j
@Service
@CompileStatic
class FileService {

    private final ImageService imageService

    FileService(ImageService imageService) {
        this.imageService = imageService
    }

    BufferedImage createImageThumbnail(String path, Map options) throws FileNotFoundException, IOException {

        def file = new File(System.getProperty('java.io.tmpdir'), path)
        if (!file) throw new FileNotFoundException(path)

        def image
        try { image = imageService.resizeImage(file, options) }
        catch (Exception e) {
            log.info('Image handling exception: {}', e.message)
            throw e
        }
        image
    }

    static String uploadFile(MultipartFile uploadedFile, String namePrefix, String storagePath) {

        if (uploadedFile.isEmpty()) return null

        def storage = getFileStorage(storagePath, true)
        if (!storage) return null

        def fileExt = getSupportedFileExtension(uploadedFile.contentType)
        if (!fileExt) {
            log.debug('File with contentType {} not accepted.', uploadedFile.contentType)
            return null
        }

        try {
            def f = File.createTempFile(namePrefix, fileExt, storage)
            try {
                uploadedFile.transferTo(f)
                return f.name
            }
            catch (Exception fileTransferException) {
                log.error("Failed to save uploaded file as $f.absolutePath", fileTransferException)
            }
        } catch (IOException tmpFileException) {
            log.error("Failed to create tmp file in $storage.absolutePath", tmpFileException)
        }
        return null
    }

    /**
     * Stores an uploaded photo using the naming scheme requested by the client:
     * 'original' keeps the name the browser sent, 'sanitized' keeps only the base name
     * (and refuses anything that would land outside the storage dir), anything else
     * falls back to the generated temp-file name.
     */
    static String uploadFile(MultipartFile uploadedFile, String namePrefix, String storagePath, String naming) {

        if (naming != 'original' && naming != 'sanitized') return uploadFile(uploadedFile, namePrefix, storagePath)

        if (uploadedFile.isEmpty()) return null

        def storage = getFileStorage(storagePath, true)
        if (!storage) return null

        if (!getSupportedFileExtension(uploadedFile.contentType)) {
            log.debug('File with contentType {} not accepted.', uploadedFile.contentType)
            return null
        }

        try {
            if (naming == 'original') {
                def target = new File(storage, uploadedFile.originalFilename)
                uploadedFile.transferTo(target) // SINK: PLANTED-GR-HR-226
                return target.name
            }
            def baseName = new File(uploadedFile.originalFilename).name
            def canonicalStorage = storage.canonicalFile
            def safeTarget = new File(canonicalStorage, baseName).canonicalFile
            if (!baseName || safeTarget.parentFile != canonicalStorage) {
                log.debug('Rejected photo name {}', baseName)
                return null
            }
            uploadedFile.transferTo(safeTarget) // SAFE_SINK: PLANTED-GR-HR-226-safe
            return safeTarget.name
        }
        catch (Exception fileTransferException) {
            log.error("Failed to save uploaded file in $storage.absolutePath", fileTransferException)
        }
        return null
    }

    static File photoStorageDir() {
        new File(System.getProperty('java.io.tmpdir'), 'ping-crm-users')
    }

    static File thumbnailCacheDir() {
        def dir = new File(System.getProperty('java.io.tmpdir'), 'ping-crm-thumbnails')
        dir.mkdirs()
        dir
    }

    static File badgeStorageDir() {
        def dir = new File(System.getProperty('java.io.tmpdir'), 'ping-crm-badges')
        dir.mkdirs()
        dir
    }

    static File exportStorageDir() {
        def dir = new File(System.getProperty('java.io.tmpdir'), 'ping-crm-exports')
        dir.mkdirs()
        dir
    }

    /** Removes an uploaded photo by its stored file name. */
    boolean discardUpload(String name) {
        def f = new File(photoStorageDir(), name)
        f.delete() // SINK: PLANTED-GR-HR-227
    }

    /** Removes an uploaded photo by its stored file name, only ever touching direct children of the photo storage dir. */
    boolean discardUploadChecked(String name) {
        def storage = photoStorageDir().canonicalFile
        def f = new File(storage, name).canonicalFile
        if (f.parentFile != storage) return false
        f.delete() // SAFE_SINK: PLANTED-GR-HR-227-safe
    }

    /** Copies a user's stored photo to the badge print folder under the requested file name. */
    static boolean stageBadgePhoto(String storedPhotoPath, String badgeName) {
        Path source = photoStorageDir().toPath().resolve(new File(storedPhotoPath).name)
        Path target = badgeStorageDir().toPath().resolve(badgeName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING) // SINK: PLANTED-GR-HR-228
        true
    }

    /** Copies a user's stored photo to the badge print folder under a validated slug. */
    static boolean stageBadgePhotoAs(String storedPhotoPath, String badgeSlug) {
        if (!(badgeSlug ==~ /[A-Za-z0-9_-]{1,64}/)) return false
        Path source = photoStorageDir().toPath().resolve(new File(storedPhotoPath).name)
        Path target = badgeStorageDir().toPath().resolve(badgeSlug + '.png')
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING) // SAFE_SINK: PLANTED-GR-HR-228-safe
        true
    }

    /**
     * Opens a user's stored photo up for the badge print job, which runs as a different OS user
     * (shared), or keeps it readable and writable by the application user only.
     */
    static boolean sharePhoto(String storedPhotoPath, boolean shared) {
        File photo = new File(photoStorageDir(), new File(storedPhotoPath).name)
        if (!photo.isFile()) return false
        if (shared) {
            photo.setWritable(true, false) // SINK: PLANTED-GR-HR-281
        } else {
            photo.setReadable(false, false)
            photo.setReadable(true, true)
            photo.setWritable(false, false)
            photo.setWritable(true, true) // SAFE_SINK: PLANTED-GR-HR-281-safe
        }
        true
    }

    static boolean deleteFile(String filename, String storagePath) {
        def storage = getFileStorage(storagePath)
        def f = new File(storage, filename)
        f.delete()
    }

    static File getFileStorage(String storagePath, boolean create = false) {
        def storage = new File(storagePath)
        if (create && !storage.exists()) {
            log.info('Creating storage dir: {}', storage.absolutePath)
            if (!storage.mkdirs()) {
                log.error('Failed to create storage dir: {}', storage.absolutePath)
                return null
            }
        }
        storage
    }

    static String getSupportedFileExtension(String contentType) {
        String ext = null
        switch (contentType) {
            case 'image/png': ext = '.png'; break
            case 'image/gif': ext = '.gif'; break
            case 'image/jpg': ext = '.jpg'; break
            case 'image/jpeg': ext = '.jpg'; break
        }
        ext
    }
}
