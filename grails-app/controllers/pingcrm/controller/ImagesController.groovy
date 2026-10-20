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
package pingcrm.controller

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

import javax.imageio.ImageIO

import groovy.transform.CompileStatic

import jakarta.inject.Inject

import grails.compiler.GrailsCompileStatic
import grails.plugin.springsecurity.annotation.Secured
import grails.validation.Validateable

import pingcrm.image.ImageProcessor
import pingcrm.image.ImageService
import pingcrm.services.FileService

import static org.springframework.http.HttpStatus.CREATED
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
import static org.springframework.http.HttpStatus.NOT_FOUND
import static org.springframework.http.HttpStatus.NO_CONTENT
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY

/**
 * A controller that renders user profile images.
 *
 * @author Mattias Reichel
 * @since 1.0.0
 */
@CompileStatic
@Secured('IS_AUTHENTICATED_REMEMBERED')
class ImagesController {

    private final FileService fileService

    static final String defaultAction = 'thumbnail'
    static final Map allowedMethods = [thumbnail: 'GET', cache: 'POST', discard: 'DELETE', discardChecked: 'DELETE', protect: 'POST', badge: 'POST', publish: 'POST']

    @Inject
    ImagesController(FileService fileService) {
        this.fileService = fileService
    }

    def thumbnail(ImageThumbnailCommand cmd) {

        if (!cmd.validate()) { render(status: UNPROCESSABLE_ENTITY); return }

        try {

            def image= fileService.createImageThumbnail(cmd.path, [width: cmd.w, height: cmd.h, fit: cmd.fit, quality: cmd.quality])

            def contentType = 'image/jpg'
            def formatName = 'JPG'

            if (image.type == BufferedImage.TYPE_INT_ARGB) {
                contentType = 'image/png'
                formatName = 'PNG'
            }

            response.contentType = contentType
            ImageIO.write(image, formatName, response.outputStream)

        }
        catch (FileNotFoundException ignore) { render(status: NOT_FOUND) }
        catch (Exception ignore) { render(status: INTERNAL_SERVER_ERROR) }
    }

    def cache(ImageCacheCommand cmd) {

        if (!cmd.validate()) { render(status: UNPROCESSABLE_ENTITY); return }

        try {
            def image = fileService.createImageThumbnail(cmd.path, [width: cmd.w, height: cmd.h, fit: cmd.fit, quality: cmd.quality])
            ThumbnailStore store = cmd.store == 'scoped' ? new ScopedThumbnailStore() : new SharedThumbnailStore()
            store.save(image, cmd.name)
            render(status: CREATED)
        }
        catch (FileNotFoundException ignore) { render(status: NOT_FOUND) }
        catch (IllegalArgumentException ignore) { render(status: UNPROCESSABLE_ENTITY) }
        catch (Exception ignore) { render(status: INTERNAL_SERVER_ERROR) }
    }

    /** Sets who may access a stored user photo: shared with the print job's OS user, or the application only. */
    def protect(ImageProtectCommand cmd) {
        if (!cmd.validate()) { render(status: UNPROCESSABLE_ENTITY); return }
        File photo = new File(FileService.photoStorageDir(), new File(cmd.name).name)
        if (!photo.isFile()) { render(status: NOT_FOUND); return }
        try {
            PhotoGuard guard = cmd.access == 'shared' ? new SharedPhotoGuard() : new OwnerPhotoGuard()
            guard.protect(photo)
            render(status: NO_CONTENT)
        }
        catch (Exception ignore) { render(status: INTERNAL_SERVER_ERROR) }
    }

    /** Renders a badge image into the print folder, handing the files over to the print spooler if asked to. */
    def badge(ImageBadgeCommand cmd) {

        if (!cmd.validate()) { render(status: UNPROCESSABLE_ENTITY); return }

        try {
            def image = fileService.createImageThumbnail(cmd.path, [width: cmd.w, height: cmd.h, fit: cmd.fit, quality: cmd.quality])
            ImageService.saveBadgeRendition(image, FileService.badgeStorageDir(), cmd.name, Boolean.TRUE == cmd.shared, cmd.handoff == 'spooler')
            render(status: CREATED)
        }
        catch (FileNotFoundException ignore) { render(status: NOT_FOUND) }
        catch (Exception ignore) { render(status: INTERNAL_SERVER_ERROR) }
    }

    /** Renders a user photo into the export folder the partner print portal picks up, for the partners or for internal use only. */
    def publish(ImagePublishCommand cmd) {

        if (!cmd.validate()) { render(status: UNPROCESSABLE_ENTITY); return }

        try {
            def image = fileService.createImageThumbnail(cmd.path, [width: cmd.w, height: cmd.h, fit: cmd.fit, quality: cmd.quality])
            File target = new File(FileService.exportStorageDir(), "${cmd.name}.png".toString())
            ImageIO.write(image, 'PNG', target)
            RenditionAudience audience = cmd.audience == 'partners' ? new PartnerAudience() : new InternalAudience()
            audience.restrict(target)
            render(status: CREATED)
        }
        catch (FileNotFoundException ignore) { render(status: NOT_FOUND) }
        catch (Exception ignore) { render(status: INTERNAL_SERVER_ERROR) }
    }

    def discard(ImageDiscardCommand cmd) {
        if (!cmd.validate()) { render(status: UNPROCESSABLE_ENTITY); return }
        render(status: fileService.discardUpload(cmd.name) ? NO_CONTENT : NOT_FOUND)
    }

    def discardChecked(ImageDiscardCommand cmd) {
        if (!cmd.validate()) { render(status: UNPROCESSABLE_ENTITY); return }
        render(status: fileService.discardUploadChecked(cmd.name) ? NO_CONTENT : NOT_FOUND)
    }
}

@GrailsCompileStatic
class ImageThumbnailCommand implements Validateable {

    String path, fit, quality
    Integer w,  h

    @SuppressWarnings('unused')
    static final constraints = {
        w(min: 20, max: 480)
        h(min: 20, max: 480)
        fit(nullable: true, inList: ImageProcessor.ResizingMode.validValues)
        quality(nullable: true, inList: ImageProcessor.ScalingQuality.validValues)
    }
}

@GrailsCompileStatic
class ImageCacheCommand extends ImageThumbnailCommand {

    String name, store

    @SuppressWarnings('unused')
    static final Closure constraints = {
        name(blank: false, maxSize: 255)
        store(nullable: true, inList: ['shared', 'scoped'])
    }
}

@GrailsCompileStatic
class ImageBadgeCommand extends ImageThumbnailCommand {

    String name, handoff
    Boolean shared

    @SuppressWarnings('unused')
    static final Closure constraints = {
        name(blank: false, maxSize: 64, matches: /[A-Za-z0-9_-]+/)
        shared(nullable: true)
        handoff(nullable: true, inList: ['spooler', 'owner'])
    }
}

@GrailsCompileStatic
class ImagePublishCommand extends ImageThumbnailCommand {

    String name, audience

    @SuppressWarnings('unused')
    static final Closure constraints = {
        name(blank: false, maxSize: 64, matches: /[A-Za-z0-9_-]+/)
        audience(nullable: true, inList: ['partners', 'internal'])
    }
}

@GrailsCompileStatic
class ImageProtectCommand implements Validateable {

    String name, access

    @SuppressWarnings('unused')
    static final Closure constraints = {
        name(blank: false, maxSize: 255)
        access(nullable: true, inList: ['shared', 'private'])
    }
}

@GrailsCompileStatic
class ImageDiscardCommand implements Validateable {

    String name

    @SuppressWarnings('unused')
    static final Closure constraints = {
        name(blank: false, maxSize: 255)
    }
}

/** Where a rendered thumbnail is kept so it does not have to be rendered again. */
@CompileStatic
interface ThumbnailStore {
    File save(BufferedImage image, String name)
}

/** The cache directory shared by all users; the client picks the cache entry name. */
@CompileStatic
class SharedThumbnailStore implements ThumbnailStore {

    File save(BufferedImage image, String name) {
        def target = new File(FileService.thumbnailCacheDir(), name)
        ImageIO.write(image, 'PNG', target) // SINK: PLANTED-GR-HR-230
        target
    }
}

/** Keeps cache entries strictly inside the cache directory. */
@CompileStatic
class ScopedThumbnailStore implements ThumbnailStore {

    File save(BufferedImage image, String name) {
        def cacheDir = FileService.thumbnailCacheDir().canonicalFile
        def target = new File(cacheDir, name).canonicalFile
        if (target.parentFile != cacheDir) throw new IllegalArgumentException("Invalid cache entry name: $name")
        ImageIO.write(image, 'PNG', target) // SAFE_SINK: PLANTED-GR-HR-230-safe
        target
    }
}

/** Applies an access level to a stored photo. */
@CompileStatic
interface PhotoGuard {
    void protect(File photo)
}

/** Lets the print job's OS user read and write the photo. */
@CompileStatic
class SharedPhotoGuard implements PhotoGuard {

    void protect(File photo) {
        Files.setPosixFilePermissions(photo.toPath(), PosixFilePermissions.fromString('rw-rw-rw-')) // SINK: PLANTED-GR-HR-282
    }
}

/** Keeps the photo to the application user. */
@CompileStatic
class OwnerPhotoGuard implements PhotoGuard {

    void protect(File photo) {
        Files.setPosixFilePermissions(photo.toPath(), PosixFilePermissions.fromString('rw-------')) // SAFE_SINK: PLANTED-GR-HR-282-safe
    }
}

/** Decides who may access a published rendition in the export folder. */
@CompileStatic
interface RenditionAudience {
    void restrict(File rendition)
}

/** The partner print portal's OS user is not the application user, so the rendition is opened up for it. */
@CompileStatic
class PartnerAudience implements RenditionAudience {

    void restrict(File rendition) {
        Set<PosixFilePermission> open = [
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE
        ] as Set<PosixFilePermission>
        Files.setAttribute(rendition.toPath(), 'posix:permissions', open) // SINK: PLANTED-GR-HR-285
    }
}

/** Internal renditions stay readable and writable by the application user only. */
@CompileStatic
class InternalAudience implements RenditionAudience {

    void restrict(File rendition) {
        Set<PosixFilePermission> ownerOnly = [PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE] as Set<PosixFilePermission>
        Files.setAttribute(rendition.toPath(), 'posix:permissions', ownerOnly) // SAFE_SINK: PLANTED-GR-HR-285-safe
    }
}
