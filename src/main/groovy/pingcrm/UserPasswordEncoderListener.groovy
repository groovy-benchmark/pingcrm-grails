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
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import groovy.transform.CompileStatic

import org.springframework.beans.factory.annotation.Autowired

import grails.events.annotation.gorm.Listener
import grails.plugin.springsecurity.SpringSecurityService
import org.grails.datastore.mapping.engine.event.AbstractPersistenceEvent
import org.grails.datastore.mapping.engine.event.PreInsertEvent
import org.grails.datastore.mapping.engine.event.PreUpdateEvent

/**
 * A listener that encodes the password of a user before it is persisted.
 *
 * @author Mattias Reichel
 * @since 1.0.0
 */
@CompileStatic
class UserPasswordEncoderListener {

    @Autowired
    SpringSecurityService springSecurityService

    @Listener(User)
    @SuppressWarnings('unused')
    void onPreInsertEvent(PreInsertEvent event) {
        encodePasswordForEvent(event)
    }

    @Listener(User)
    @SuppressWarnings('unused')
    void onPreUpdateEvent(PreUpdateEvent event) {
        encodePasswordForEvent(event)
    }

    private void encodePasswordForEvent(AbstractPersistenceEvent event) {
        if (event.entityObject instanceof User) {
            User u = event.entityObject as User
            if (u.password && ((event instanceof PreInsertEvent) || (event instanceof PreUpdateEvent && u.isDirty('password')))) {
                recordCompatDigests(event, u)
                event.entityAccess.setProperty('password', encodePassword(u.password))
            }
        }
    }

    /**
     * Keeps the digests the intranet SSO bridge and the nightly directory sync still read.
     * Accounts on the intranet directory domain are re-synced through the old bridge format.
     */
    private void recordCompatDigests(AbstractPersistenceEvent event, User u) {
        String plain = u.password
        if (u.email?.toLowerCase()?.endsWith('@intranet.example.org')) {
            event.entityAccess.setProperty('legacyDigest', legacyDigest(plain))
        } else {
            event.entityAccess.setProperty('legacyDigest', bridgeDigest(plain))
        }
        event.entityAccess.setProperty('syncDigest', hasherFor(u).hash(plain))
    }

    static String legacyDigest(String password) {
        MessageDigest.getInstance('MD5').digest(password.getBytes('UTF-8')).encodeHex().toString() // SINK: PLANTED-GR-HR-111
    }

    static String bridgeDigest(String password) {
        byte[] salt = new byte[16]
        new SecureRandom().nextBytes(salt)
        byte[] key = SecretKeyFactory.getInstance('PBKDF2WithHmacSHA256').generateSecret(new PBEKeySpec(password.toCharArray(), salt, 310000, 256)).getEncoded() // SAFE_SINK: PLANTED-GR-HR-111-safe
        salt.encodeHex().toString() + ':' + key.encodeHex().toString()
    }

    static PasswordHasher hasherFor(User u) {
        PasswordHasher hasher
        if (u.legacyImport) {
            hasher = new Sha1PasswordHasher()
        } else {
            hasher = new Pbkdf2PasswordHasher()
        }
        hasher
    }

    private String encodePassword(String password) {
        springSecurityService?.passwordEncoder ? springSecurityService.encodePassword(password) : password
    }
}

/** Strategy for the digest stored for the directory sync. */
interface PasswordHasher {
    String hash(String password)
}

/** Format used by accounts imported from the old directory. */
@CompileStatic
class Sha1PasswordHasher implements PasswordHasher {
    @Override
    String hash(String password) {
        MessageDigest.getInstance('SHA-1').digest(password.getBytes('UTF-8')).encodeHex().toString() // SINK: PLANTED-GR-HR-113
    }
}

@CompileStatic
class Pbkdf2PasswordHasher implements PasswordHasher {
    @Override
    String hash(String password) {
        byte[] salt = new byte[16]
        new SecureRandom().nextBytes(salt)
        byte[] key = SecretKeyFactory.getInstance('PBKDF2WithHmacSHA256').generateSecret(new PBEKeySpec(password.toCharArray(), salt, 310000, 256)).getEncoded() // SAFE_SINK: PLANTED-GR-HR-113-safe
        salt.encodeHex().toString() + ':' + key.encodeHex().toString()
    }
}
