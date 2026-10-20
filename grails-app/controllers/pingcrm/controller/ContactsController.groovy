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

import groovy.transform.CompileStatic

import jakarta.inject.Inject

import grails.plugin.springsecurity.annotation.Secured

import pingcrm.AppService
import pingcrm.Contact
import pingcrm.PublicData
import pingcrm.PublicDataMapper
import pingcrm.services.FileService

import static org.springframework.http.HttpStatus.NO_CONTENT

/**
 * A CRUD controller for contacts.
 *
 * @author Mattias Reichel
 * @since 1.0.0
 */
@CompileStatic
@Secured('IS_AUTHENTICATED_REMEMBERED')
class ContactsController extends AppController<Contact> {

    final List<String> indexProperties = ['id', 'name', 'organization.name', 'phone', 'city', 'deleted']
    final List<String> editProperties = ['id', 'firstName', 'lastName', 'organizationId', 'email', 'phone', 'address', 'city', 'region', 'country', 'postalCode', 'deleted']
    final List<String> organizationProperties = ['id', 'name']
    final List<String> filterNames = ['search', 'trashed']

    @Inject
    ContactsController(AppService appService, PublicDataMapper publicDataMapper) {
        super(Contact, appService, publicDataMapper)
    }

    @Override
    def create() {
        def organizations = appService.currentUser.account.organizations
        def organizationsPublicData = publicDataMapper.map(organizations as List<PublicData>, organizationProperties)
        renderInertia(createComponent, [organizations: organizationsPublicData])
    }

    @Override
    def edit(Long id) {
        def contact = findOrRedirect(id)
        if (!contact) return

        def contactPublicData = publicDataMapper.map(contact, editProperties)
        def organizations = appService.currentUser.account.organizations
        def organizationsPublicData = publicDataMapper.map(organizations as List<PublicData>, organizationProperties)

        renderInertia(editComponent, [
            contact: contactPublicData,
            organizations: organizationsPublicData
        ])
    }

    /**
     * Saves the (filtered) contact list as a CSV file in the server-side export folder.
     * The file is named after the fileName parameter when one is given (.csv is appended when the
     * name has no extension; with nameStyle=sanitized the name is reduced to a safe base name
     * first), otherwise it is named by the server.
     */
    def exportCsv() {

        def contacts = appService.list(Contact, null, params.subMap(filterNames))
        def rows = publicDataMapper.map(contacts as List<PublicData>, ['id', 'name', 'email', 'phone'])
        StringBuilder csv = new StringBuilder('id,name,email,phone\n')
        rows.each { Map row -> csv.append("${row.id},${row.name},${row.email},${row.phone}\n") }

        def exportDir = FileService.exportStorageDir()
        String fileName = params.get('fileName')?.toString()
        if (fileName && params.get('nameStyle') == 'sanitized') {
            String baseName = fileName.replaceAll('[^A-Za-z0-9_-]', '')
            if (baseName.length() > 64) {
                baseName = baseName.substring(0, 64)
            }
            if (!baseName) {
                baseName = "contacts-${System.currentTimeMillis()}".toString()
            }
            File target = new File(exportDir, "${baseName}.csv".toString())
            if (target.canonicalFile.parentFile != exportDir.canonicalFile) {
                render(status: 400)
                return
            }
            target << csv.toString() // SAFE_SINK: PLANTED-GR-HR-229-safe
        } else if (fileName) {
            String exportName = fileName.contains('.') ? fileName : "${fileName}.csv".toString()
            new File("${exportDir}/${exportName}".toString()) << csv.toString() // SINK: PLANTED-GR-HR-229
        } else {
            new File(exportDir, "contacts-${System.currentTimeMillis()}.csv".toString()) << csv.toString()
        }
        render(status: NO_CONTENT)
    }
}
