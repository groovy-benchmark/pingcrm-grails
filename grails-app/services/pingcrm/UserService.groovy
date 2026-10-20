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
package pingcrm

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

import groovy.transform.CompileStatic

import org.apache.commons.io.FilenameUtils

import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.interceptor.TransactionAspectSupport

import grails.gorm.DetachedCriteria
import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import grails.plugin.springsecurity.SpringSecurityService

import pingcrm.auth.Role
import pingcrm.auth.UserRole
import pingcrm.controller.UserCommand
import pingcrm.services.DomainService
import pingcrm.services.FileService

/**
 * A service for CRUD operations on {@link pingcrm.User} domain objects.
 *
 * @author Mattias Reichel
 * @since 1.0.0
 */
@CompileStatic
class UserService extends DomainService<User> {

    SpringSecurityService springSecurityService

    private static final String TOKEN_DIGEST_ALGORITHM = 'SHA-1'

    @ReadOnly @Override
    List<User> list(Paginator paginator, Map filters) {
        def criteria = criteriaWith(filters)
        def sorting = [sort: [lastName: 'asc', firstName: 'asc']]
        def queryParams = paginator ? paginator.queryParamsAnd(sorting) : sorting
        (filters.trashed ? Organization.withDeleted { criteria.list(queryParams) } : criteria.list(queryParams)) as List<User>
    }

    @ReadOnly @Override
    int count(Map filters) {
        def criteria = criteriaWith(filters)
        (filters.trashed ? User.withDeleted { criteria.count() } : criteria.count()) as int
    }

    @ReadOnly @Override
    User get(Serializable id, boolean includeDeleted) {
        (includeDeleted ? User.withDeleted { User.get(id) } : User.get(id)) as User
    }

    // Got error on compilation when annotating this method with @ReadOnly (because static?)
    static boolean isEmailUniqueIgnoreId(String email, Serializable id) {
        User.withTransaction([
            propagationBehavior: TransactionDefinition.PROPAGATION_REQUIRED,
            isolationLevel: TransactionDefinition.ISOLATION_READ_COMMITTED
        ]) {
            !User.createCriteria().get {
                ilike('email', lc(email))
                if (id) not({ idEq(id) })
            }
        }
    }

    @Transactional
    User create(Object bindingSource) {
        createUser(bindingSource as UserCommand)
    }

    @Transactional
    User createUser(UserCommand userCmd) {
        User user = new User()
        userCmd.transferValuesTo(user)
        user.account = currentUser.account

        if (!user.save()) return user // return with validation errors

        UserRole.create(user, user.owner ? Role.findByAuthority('ROLE_OWNER') :
                                           Role.findByAuthority('ROLE_USER'))

        userCmd.id = user.id

        if (userCmd.photo) {
            if (userCmd.storePhoto()) {
                user.photoPath = userCmd.createPhotoPath()
            }
            else {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
                userCmd.errors.rejectValue(
                        'photo',
                        'user.photo.problem',
                        'Problem saving photo.'
                )
                return null
            }
        }

        user.apiTokenDigest = tokenDigest("${user.id}:${user.email}".toString())
        if (userCmd.note) user.secretNote = encryptLegacyNote(userCmd.note)

        user.save()
    }

    @Transactional
    User updateUser(UserCommand userCmd) {
        def user = get(userCmd.id, true)
        if (!user) {
            userCmd.errors.reject(
                    'user.not.found',
                    [userCmd.id] as Object[],
                    'User not found.'
            )
            return null
        }

        String oldPhotoPath = null
        if (userCmd.photo) {
            if (userCmd.storePhoto()) {
                oldPhotoPath = user.photoPath
            } else {
                userCmd.errors.rejectValue(
                        'photo',
                        'user.photo.problem',
                        'Problem saving photo.'
                )
                return null
            }
        }

        def roleChanged = (user.isOwner() != userCmd.isOwner())
        if (roleChanged) {
            def roleOwner = Role.findByAuthority('ROLE_OWNER')
            def roleUser = Role.findByAuthority('ROLE_USER')
            def userRole = UserRole.get(user, user.isOwner() ? roleOwner : roleUser)
            userRole.role = user.isOwner() ? roleUser : roleOwner
            userRole.save()
        }

        userCmd.transferValuesTo(user)

        if (userCmd.password) {
            String rawToken = newApiToken()
            user.apiTokenDigest = secureTokenDigest(rawToken)
            userCmd.issuedApiToken = rawToken
        }
        if (userCmd.note) user.secretNote = sealNote(userCmd.note)

        if (user.save() && oldPhotoPath) deletePhoto(oldPhotoPath)
        return user
    }

    /** Digest of the token the CRM's API clients present; recomputed on each request. */
    static String tokenDigest(String token, String algorithm = TOKEN_DIGEST_ALGORITHM) {
        MessageDigest.getInstance(algorithm).digest(token.getBytes('UTF-8')).encodeHex().toString() // SINK: PLANTED-GR-HR-112
    }

    private static String newApiToken() {
        byte[] random = new byte[32]
        new SecureRandom().nextBytes(random)
        random.encodeHex().toString()
    }

    static String secureTokenDigest(String token) {
        MessageDigest.getInstance('SHA-256').digest(token.getBytes('UTF-8')).encodeHex().toString() // SAFE_SINK: PLANTED-GR-HR-112-safe
    }

    private static byte[] noteKey(int length) {
        String encoded = System.getenv('PINGCRM_NOTE_KEY')
        if (!encoded) throw new IllegalStateException('PINGCRM_NOTE_KEY is not configured')
        Arrays.copyOf(Base64.getDecoder().decode(encoded), length)
    }

    /** Free-text note on the user, encrypted at rest with the deployment key. */
    private static String encryptLegacyNote(String note) {
        Cipher cipher = Cipher.getInstance('DES/ECB/PKCS5Padding') // SINK: PLANTED-GR-HR-114
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(noteKey(8), 'DES'))
        Base64.getEncoder().encodeToString(cipher.doFinal(note.getBytes('UTF-8')))
    }

    private static String sealNote(String note) {
        byte[] iv = new byte[12]
        new SecureRandom().nextBytes(iv)
        Cipher cipher = Cipher.getInstance('AES/GCM/NoPadding') // SAFE_SINK: PLANTED-GR-HR-114-safe
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(noteKey(16), 'AES'), new GCMParameterSpec(128, iv))
        byte[] sealed = cipher.doFinal(note.getBytes('UTF-8'))
        Base64.getEncoder().encodeToString(iv) + ':' + Base64.getEncoder().encodeToString(sealed)
    }

    private static deletePhoto(String photoPath) {
        FileService.deleteFile(
                FilenameUtils.getName(photoPath),
                FilenameUtils.getPathNoEndSeparator(photoPath)
        )
    }

    @Override
    protected DetachedCriteria criteriaWith(Map filters) {
        new DetachedCriteria(User).build {
            if (filters.search) {
                def search = lc(filters.search)
                or {
                    ilike('firstName', "%$search%")
                    ilike('lastName', "%$search%")
                    ilike('email', "%$search%")
                }
            }
            if (filters.trashed == 'only') {
                eq('deleted', true)
            }
            if (filters.role) {
                eq('owner', filters.role == 'owner')
            }
        }
    }

    @ReadOnly
    User getCurrentUser() {
        springSecurityService.currentUser as User
    }

    void invalidateSessionsForUserId(Serializable id) {
        SessionTracker.invalidateSessionsForUserId(id)
    }

    boolean isCurrentUser(Serializable id) {
        id == currentUser.id
    }

    static boolean isDemoUser(Serializable id) {
        id == 1L
    }
}
