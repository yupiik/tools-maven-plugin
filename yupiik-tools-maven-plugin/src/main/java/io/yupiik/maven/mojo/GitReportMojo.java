/*
 * Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.maven.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.yupiik.maven.mojo.GitReportMojo.GitLogFormat.Entry.BACKQUOTE;
import static io.yupiik.maven.mojo.GitReportMojo.GitLogFormat.Entry.CLOSE_BRACKET;
import static io.yupiik.maven.mojo.GitReportMojo.GitLogFormat.Entry.DATE;
import static io.yupiik.maven.mojo.GitReportMojo.GitLogFormat.Entry.MESSAGE;
import static io.yupiik.maven.mojo.GitReportMojo.GitLogFormat.Entry.OPEN_BRACKET;
import static io.yupiik.maven.mojo.GitReportMojo.GitLogFormat.Entry.SHA;
import static io.yupiik.maven.mojo.GitReportMojo.GitLogFormat.Entry.SPACE;
import static io.yupiik.maven.mojo.GitReportMojo.GitLogFormat.Entry.STAR;
import static java.util.Comparator.comparing;
import static java.util.Comparator.reverseOrder;
import static java.util.Locale.ROOT;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

/**
 * Generates a report from a {@code .git} folder.
 */
@Mojo(name = "git-report", requiresProject = false, threadSafe = true)
public class GitReportMojo extends AbstractMojo {
    /**
     * "Where .git folder to analyze is.
     */
    @Parameter(property = "yupiik.git-report.dotGit", required = true)
    protected File dotGit;

    /**
     * How to format a log line if the renderer needs to.
     */
    @Parameter(property = "yupiik.git-report.logFormat")
    protected GitLogFormat logFormat;

    /**
     * Options to filter the git log value.
     */
    @Parameter(property = "yupiik.git-report.filters")
    protected GitLogFilter filters;

    /**
     * Where to write the report.
     */
    @Parameter(property = "yupiik.git-report.target", defaultValue = "${project.build.directory}/yupiik-git/report.adoc")
    protected File target;

    /**
     * Report title.
     */
    @Parameter(property = "yupiik.git-report.title", defaultValue = "Report")
    protected String title;

    /**
     * Kind of rendering.
     */
    @Parameter(property = "yupiik.git-report.renderer", defaultValue = "DEFAULT")
    protected Renderer[] renderers;

    /**
     * If true existing report is overwritten else it fails if it already exists.
     */
    @Parameter(property = "yupiik.git-report.overwrite", defaultValue = "true")
    protected boolean overwrite;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var dotGitPath = dotGit.toPath();
        final var output = target.toPath().toAbsolutePath().normalize();
        if (Files.exists(output) && !overwrite) {
            throw new MojoExecutionException("Report '" + target + "' already exists, set --overwrite=true or change path.");
        }
        try {
            doExecute(dotGitPath, output);
        } catch (final IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void doExecute(final Path dotGitPath, final Path output) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
            getLog().info("Created '" + output.getParent() + "'");
        }

        final Predicate<RevCommit> filters = this.filters == null ? r -> true : this.filters;
        final GitLogFormat format = this.logFormat == null ? new GitLogFormat() : this.logFormat;
        if (format.entries == null) { // new instance because maven didn't set it
            format.entries = List.of(
                    STAR, SPACE,
                    OPEN_BRACKET, BACKQUOTE, SHA, BACKQUOTE, CLOSE_BRACKET,
                    OPEN_BRACKET, DATE, CLOSE_BRACKET, SPACE,
                    MESSAGE);
        }

        try (final var git = Git.open(dotGitPath.toFile())) {
            final var asciidoc = "" +
                    // todo: more config, "prefix"?
                    "= " + title + "\n" +
                    "Yupiik <contact@yupiik.com>\n" +
                    // ":toc:\n" + // injected with asciidoctor renderer attribute (pdf renderer for ex)
                    "\n" + Stream.of(renderers)
                    .flatMap(Renderer::flatten)
                    .map(r -> {
                        try {
                            return r.render(git, filters, format);
                        } catch (final IOException | GitAPIException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .collect(joining("\n\n"));

            if (getLog().isDebugEnabled()) {
                getLog().debug("Document: " + asciidoc);
            }
            try (final var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                writer.write(asciidoc);
            }
        }
    }

    public static class GitLogFormat implements Function<RevCommit, String> {
        @Parameter(defaultValue = "" +
                "STAR,SPACE," +
                "OPEN_BRACKET,BACKQUOTE,SHA,BACKQUOTE,CLOSE_BRACKET," +
                "OPEN_BRACKET,DATE,CLOSE_BRACKET,SPACE," +
                "MESSAGE")
        private List<Entry> entries;

        public void setEntries(final List<Entry> entries) {
            this.entries = entries;
        }

        @Override
        public String apply(final RevCommit commit) {
            return entries.stream().map(it -> it.render(commit)).collect(joining());
        }

        public enum Entry {
            STAR {
                @Override
                public String render(final RevCommit commit) {
                    return "*";
                }
            },
            BACKQUOTE {
                @Override
                public String render(final RevCommit commit) {
                    return "`";
                }
            },
            COLON {
                @Override
                public String render(final RevCommit commit) {
                    return ":";
                }
            },
            SPACE {
                @Override
                public String render(final RevCommit commit) {
                    return " ";
                }
            },
            OPEN_BRACKET {
                @Override
                public String render(final RevCommit commit) {
                    return "[";
                }
            },
            CLOSE_BRACKET {
                @Override
                public String render(final RevCommit commit) {
                    return "]";
                }
            },
            OPEN_PARENTHESIS {
                @Override
                public String render(final RevCommit commit) {
                    return "(";
                }
            },
            CLOSE_PARENTHESIS {
                @Override
                public String render(final RevCommit commit) {
                    return ")";
                }
            },
            SHA {
                @Override
                public String render(final RevCommit commit) {
                    return commit.getId().abbreviate(7).name().strip();
                }
            }, DATE {
                @Override
                public String render(final RevCommit commit) {
                    final var base = ofNullable(commit.getCommitterIdent()).orElseGet(commit::getAuthorIdent);
                    return LocalDateTime.ofInstant(base.getWhen().toInstant(), base.getTimeZone().toZoneId()).toString();
                }
            }, AUTHOR {
                @Override
                public String render(final RevCommit commit) {
                    return commit.getCommitterIdent().getName();
                }
            }, MAIL {
                @Override
                public String render(final RevCommit commit) {
                    return commit.getCommitterIdent().getEmailAddress();
                }
            }, MESSAGE {
                @Override
                public String render(final RevCommit commit) {
                    return commit.getShortMessage();
                }
            }, FULL_MESSAGE {
                @Override
                public String render(final RevCommit commit) {
                    return commit.getFullMessage();
                }
            };

            public abstract String render(final RevCommit commit);
        }
    }


    public static class GitLogFilter implements Predicate<RevCommit> {
        private Predicate<RevCommit> delegate = r -> true;

        /**
         * Starting date (yyyyMMdd format), included.
         */
        public void setFrom(final String from) {
            final var date = parseDate(from).minusDays(1);
            delegate = delegate.and(c -> date.isBefore(toDate(c)));
        }

        /**
         * Ending date (yyyyMMdd format), included.
         */
        public void setTo(final String to) {
            final var date = parseDate(to).plusDays(1);
            delegate = delegate.and(c -> date.isAfter(toDate(c)));
        }

        /**
         * Mail should match the provided regex (java).
         */
        public void setMailRegex(final String regex) {
            final var pattern = Pattern.compile(regex);
            delegate = delegate.and(c -> pattern.matcher(c.getCommitterIdent().getEmailAddress()).matches());
        }

        private LocalDate toDate(final RevCommit c) {
            final var base = ofNullable(c.getCommitterIdent()).orElseGet(c::getAuthorIdent);
            return LocalDate.ofInstant(base.getWhen().toInstant(), base.getTimeZone().toZoneId());
        }

        private LocalDate parseDate(final String value) {
            return LocalDate.parse(value.strip(), DateTimeFormatter.BASIC_ISO_DATE);
        }

        @Override
        public boolean test(final RevCommit commit) {
            return delegate.test(commit);
        }
    }


    public enum Renderer {
        DEFAULT {
            @Override
            public String render(final Git git, final Predicate<RevCommit> filters, final Function<RevCommit, String> renderer) {
                throw new UnsupportedOperationException("DEFAULT is an alias, shouldn't be executed.");
            }

            @Override
            public Stream<Renderer> flatten() {
                return Stream.of(REPOSITORY_METADATA, COMMITS_PER_NAME_PREFIX_CHRONOLOGICAL);
            }
        },
        REPOSITORY_METADATA {
            @Override
            public String render(final Git git, final Predicate<RevCommit> filters, final Function<RevCommit, String> renderer) throws GitAPIException {
                return git.remoteList().call().stream()
                        .filter(it -> !it.getURIs().isEmpty())
                        .min(comparing(i -> {
                            if ("origin".equals(i.getName())) {
                                return 0;
                            }
                            return i.getName().hashCode();
                        }))
                        .map(it -> it.getURIs().get(0))
                        .map(uri -> "" +
                                "== Repository\n" +
                                "\n" +
                                "|===\n" +
                                "|Name | " + uri.getHumanishName() + "\n" +
                                "|URL | " + uri + "\n" +
                                "|===\n" +
                                "\n")
                        .orElse("");
            }
        },
        COMMITS {
            @Override
            public String render(final Git git, final Predicate<RevCommit> filters, final Function<RevCommit, String> renderer) throws GitAPIException {
                final var logs = listCommits(git, filters);
                return logs.isEmpty() ? "" : ("== Commits (reverse order)\n\n" + renderCommits(logs, c -> formatCommit(c, renderer)));
            }
        },
        COMMITS_CHRONOLOGICAL {
            @Override
            public String render(final Git git, final Predicate<RevCommit> filters, final Function<RevCommit, String> renderer) throws GitAPIException {
                final var logs = listCommits(git, filters);
                logs.sort(reverseOrder());
                return logs.isEmpty() ? "" : ("== Commits\n\n" + renderCommits(logs, c -> formatCommit(c, renderer)));
            }
        },
        COMMITS_PER_EMAIL_PREFIX {
            @Override
            public String render(final Git git, final Predicate<RevCommit> filters, final Function<RevCommit, String> renderer) throws GitAPIException {
                final var logs = listCommits(git, filters);
                if (logs.isEmpty()) {
                    return "";
                }
                return "== Commits Per Author (reverse order)\n\n" + renderPerGroup(groupByMail(logs), renderer);
            }
        },
        COMMITS_PER_EMAIL_PREFIX_CHRONOLOGICAL {
            @Override
            public String render(final Git git, final Predicate<RevCommit> filters, final Function<RevCommit, String> renderer) throws GitAPIException {
                final var logs = listCommits(git, filters);
                if (logs.isEmpty()) {
                    return "";
                }
                return "== Commits Per Author\n\n" + renderPerGroup(groupByMail(logs), renderer);
            }
        },
        COMMITS_PER_NAME_PREFIX {
            @Override
            public String render(final Git git, final Predicate<RevCommit> filters, final Function<RevCommit, String> renderer) throws GitAPIException {
                final var logs = listCommits(git, filters);
                if (logs.isEmpty()) {
                    return "";
                }
                return "== Commits Per Author (reverse order)\n\n" + renderPerGroup(groupByName(logs), renderer);
            }
        },
        COMMITS_PER_NAME_PREFIX_CHRONOLOGICAL {
            @Override
            public String render(final Git git, final Predicate<RevCommit> filters, final Function<RevCommit, String> renderer) throws GitAPIException {
                final var logs = listCommits(git, filters);
                if (logs.isEmpty()) {
                    return "";
                }
                return "== Commits Per Author\n\n" + renderPerGroup(groupByName(logs), renderer);
            }
        };

        public abstract String render(Git git, Predicate<RevCommit> filters, Function<RevCommit, String> renderingOptions) throws GitAPIException, IOException;

        protected Map<String, List<RevCommit>> groupByMail(final List<RevCommit> logs) {
            return logs.stream()
                    .collect(groupingBy(i -> ofNullable(i.getCommitterIdent().getEmailAddress())
                            .map(m -> {
                                final int sep = m.indexOf('@');
                                return sep > 0 ? m.substring(0, sep) : m;
                            })
                            .orElse("unknown")));
        }

        protected Map<String, List<RevCommit>> groupByName(final List<RevCommit> logs) {
            return logs.stream()
                    .collect(groupingBy(i -> ofNullable(i.getCommitterIdent())
                            .map(PersonIdent::getName)
                            .map(n -> n.toLowerCase(ROOT))
                            .orElse("unknown")));
        }

        protected String renderPerGroup(final Map<String, List<RevCommit>> perMail, final Function<RevCommit, String> renderer) {
            return perMail.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> {
                        final var name = e.getValue().get(0).getCommitterIdent().getName();
                        return "" +
                                "=== " +
                                // use email if it is the key, else use the name
                                (e.getKey().contains("@") ? e.getKey() : e.getValue().get(0).getCommitterIdent().getName()) +
                                // if we used the email show the name and commit counter, else just the commit counter
                                (!e.getKey().equalsIgnoreCase(name) ?
                                        " (" + name + ", #" + e.getValue().size() + ")" :
                                        " (#" + e.getValue().size() + ")") + "\n" +
                                "\n" +
                                renderCommits(e.getValue(), c -> formatCommit(c, renderer));
                    })
                    .collect(joining("\n\n"));
        }

        protected String renderCommits(final List<RevCommit> logs, final Function<RevCommit, String> formatter) {
            return logs.stream()
                    .map(formatter)
                    .collect(joining("\n", "", "\n"));
        }

        protected List<RevCommit> listCommits(final Git git, final Predicate<RevCommit> filter) throws GitAPIException {
            return stream(git.log().call().spliterator(), false).filter(filter).collect(toList());
        }

        protected String formatCommit(final RevCommit commit, final Function<RevCommit, String> renderer) {
            return renderer.apply(commit);
        }

        protected String toShortHash(final RevCommit commit) {
            return commit.getId().abbreviate(7).name();
        }

        public Stream<Renderer> flatten() {
            return Stream.of(this);
        }
    }
}
