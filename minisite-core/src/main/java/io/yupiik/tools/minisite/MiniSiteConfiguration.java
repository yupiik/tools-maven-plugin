/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.tools.minisite;

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.language.Asciidoc;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = PRIVATE)
public class MiniSiteConfiguration {
    private Supplier<ClassLoader> actionClassLoader;
    private Path source;
    private Path target;
    private List<File> templateDirs;
    private Map<String, Object> attributes;
    private String title;
    private String description;
    private String rssFeedFile;
    private String logoText;
    private String logo;
    private String indexText;
    private String indexSubTitle;
    private String copyright;
    private String linkedInCompany;
    private String customHead;
    private String customScripts;
    private String customMenu;
    private String siteBase;
    private boolean useDefaultAssets;
    private String searchIndexName;
    private List<String> notIndexedPages;
    private boolean generateBlog;
    private int blogPageSize;
    private boolean generateIndex;
    private boolean generateSiteMap;
    private List<String> templatePrefixes;
    private boolean templateAddLeftMenu;
    private List<String> templateSuffixes;
    private List<PreAction> preActions;
    private boolean skipRendering;
    private String customGems;
    private String projectVersion;
    private String projectName;
    private String projectArtifactId;
    private List<String> requires;
    private AsciidoctorConfiguration asciidoctorConfiguration;
    private Asciidoc asciidoc;
    private boolean reverseBlogOrder;
    private boolean addIndexRegistrationPerCategory;
    private boolean skipIndexTitleDocumentationText;
    private String logoSideText;
    private Map<String, BlogCategoryConfiguration> blogCategoriesCustomizations;
    private Map<String, String> templateExtensionPoints;
    private boolean injectYupiikTemplateExtensionPoints;
    private boolean injectBlogMeta;
    private String blogPublicationDate;
    private OffsetDateTime runtimeBlogPublicationDate;
    private GravatarConfiguration gravatar = new GravatarConfiguration();
    private boolean addCodeCopyButton = true;

    public void fixConfig() {
        if (requires == null) { // ensure we don't load reveal.js by default since we disabled extraction of gems
            requires = List.of();
        }
        if (blogCategoriesCustomizations == null) {
            blogCategoriesCustomizations = Map.of();
        }

        if (blogPublicationDate == null) {
            runtimeBlogPublicationDate = OffsetDateTime.now();
        } else {
            switch (blogPublicationDate) {
                case "today":
                    runtimeBlogPublicationDate = OffsetDateTime.now();
                    break;
                case "yesterday":
                    runtimeBlogPublicationDate = OffsetDateTime.now().minusDays(1);
                    break;
                case "tomorrow":
                    runtimeBlogPublicationDate = OffsetDateTime.now().plusDays(1);
                    break;
                case "infinite":
                    runtimeBlogPublicationDate = OffsetDateTime.MAX;
                    break;
                default:
                    try {
                        runtimeBlogPublicationDate = OffsetDateTime.parse(blogPublicationDate.strip());
                    } catch (final DateTimeParseException dtpe) {
                        runtimeBlogPublicationDate = LocalDate.parse(blogPublicationDate.strip()).atTime(LocalTime.MIN.atOffset(ZoneOffset.UTC));
                    }
            }
        }

        // ensure writable
        templateExtensionPoints = new HashMap<>(templateExtensionPoints == null ? Map.of() : templateExtensionPoints);
        // ensure there are (dynamic defaults) for built-in custom extension points (empty for not yupiik case)
        // note that for static ones, they can be put in resources
        templateExtensionPoints.putIfAbsent("socialLinks", (injectYupiikTemplateExtensionPoints ? "" +
                // add github
                "<li class=\"list-inline-item\"><a title=\"Github\" href=\"https://www.github.com/{{linkedInCompany}}/\"><i class=\"fab fa-github fa-fw\"></i></a></li>" +
                "" : "") +
                // always linkedin
                "<li class=\"list-inline-item\"><a title=\"LinkedIn\" href=\"https://www.linkedin.com/company/{{linkedInCompany}}/\"><i class=\"fab fa-linkedin fa-fw\"></i></a></li>");
        templateExtensionPoints.putIfAbsent("copyrightLine", "" +
                "<small class=\"copyright\">{{copyright}}</small>" + (injectYupiikTemplateExtensionPoints ? "" +
                // add terms of service and privacy policy
                " | <a href=\"https://www.{{linkedInCompany}}.com/terms-of-use/\">Terms of use</a> | " +
                "<a href=\"https://www.{{linkedInCompany}}.com/privacy-policy/\">Privacy policy</a>" : ""));
        templateExtensionPoints.putIfAbsent("socialLinksFooter", templateExtensionPoints.get("socialLinks"));
    }

    @Data
    public static class BlogCategoryConfiguration {
        private int order = -1;
        private String homePageName;
        private String icon;
        private String description;
    }

    @Data
    public static class GravatarConfiguration {
        private String url = "https://www.gravatar.com/avatar/%s?d=identicon&size=40";
        private Function<String, String> nameToMailMapper = this::defaultMailMappingStrategy;

        public String defaultMailMappingStrategy(final String name) {
            final String[] segments = name.split(" ");
            return Character.toLowerCase(segments[0].charAt(0)) +
                    Stream.of(segments).skip(1).map(it -> it.replace("-", "").toLowerCase(Locale.ROOT)).collect(joining()) + "@yupiik.com";
        }

        /**
         * Enables to specify how to map an author name to a mail.
         * It uses a properties syntax.
         * {@code default} enables to force the default implementation to be used.
         *
         * @param config configuration in properties format.
         */
        public void setMailMappingStrategy(final String config) { // enables to be set from maven config or any IoC
            if ("default".equals(config)) {
                nameToMailMapper = this::defaultMailMappingStrategy;
                return;
            }
            final Properties props = new Properties();
            try (final StringReader reader = new StringReader(config)) {
                try {
                    props.load(reader);
                } catch (final IOException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            switch (props.getProperty("strategy", "custom")) {
                case "default":
                    nameToMailMapper = this::defaultMailMappingStrategy;
                    break;
                case "custom":
                default:
                    final String mail = '@' + props.getProperty("mail", "yupiik.com");
                    final boolean firstNameFirstLetter = Boolean.parseBoolean(props.getProperty("firstName.firstLetter", "true"));
                    final boolean firstNameLowerCase = Boolean.parseBoolean(props.getProperty("firstName.lower", "true"));
                    final String lastNameConcatenationSep = props.getProperty("lastName.concatenationSeparator", "");
                    final boolean lastNameStripNotAlphaChars = Boolean.parseBoolean(props.getProperty("lastName.stripNotAlphaChars", "true"));
                    final boolean lastNameLowerCase = Boolean.parseBoolean(props.getProperty("lastName.lower", "true"));
                    final String firstNameLastNameSeparator = props.getProperty("names.separator", "");
                    nameToMailMapper = name -> {
                        final String[] segments = name.split(" ");

                        String firstname = segments[0];
                        if (firstNameFirstLetter) {
                            firstname = firstname.substring(0, 1);
                        }
                        if (firstNameLowerCase) {
                            firstname = firstname.toLowerCase(Locale.ROOT);
                        }

                        final String lastName = Stream.of(segments)
                                .skip(1)
                                .map(it -> {
                                    String value = it;
                                    if (lastNameStripNotAlphaChars) {
                                        value = value.replaceAll("[\\W]", "");
                                    }
                                    if (lastNameLowerCase) {
                                        value = value.toLowerCase(Locale.ROOT);
                                    }
                                    return value;
                                })
                                .collect(joining(lastNameConcatenationSep));
                        return firstname + firstNameLastNameSeparator + lastName + mail;
                    };
            }
        }
    }
}
