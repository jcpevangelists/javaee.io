package com.tomitribe.io.www

import groovy.json.JsonSlurper
import org.yaml.snakeyaml.Yaml

import javax.annotation.PostConstruct
import javax.annotation.Resource
import javax.ejb.Lock
import javax.ejb.LockType
import javax.ejb.Singleton
import javax.ejb.Timeout
import javax.ejb.Timer
import javax.ejb.TimerService
import javax.inject.Inject
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

@Singleton
@Lock(LockType.READ)
class ServiceGithub {
    private Logger logger = Logger.getLogger('tribeio')

    public static final int UPDATE_INTERVAL = TimeUnit.MINUTES.toMillis(60)

    private List<DtoProject> projects
    private Set<DtoContributor> contributors
    private Set<DtoContributions> contributions

    @Inject
    private HttpBean http

    @Resource
    private TimerService timerService

    private Timer timer

    @PostConstruct
    void init() {
        timer = timerService.createTimer(0, 'First time ServiceGithub load')
    }

    def getContributors(String projectName) {
        def url = "https://api.github.com/repos/tomitribe/$projectName/contributors"
        new JsonSlurper().parseText(http.getUrlContentWithToken(url)).collect {
            def githubContributor = new JsonSlurper().parseText(
                    http.getUrlContentWithToken("https://api.github.com/users/${it.login}")
            )
            [
                    contributor  : new DtoContributor(
                            login: it.login,
                            avatarUrl: it.avatar_url,
                            name: githubContributor.name,
                            company: githubContributor.company,
                            location: githubContributor.location
                    ),
                    contributions: new DtoContributions(
                            project: projectName,
                            login: it.login,
                            contributions: it.contributions as Integer
                    )
            ]
        }
    }

    private List<DtoProject> sortMyConfigFile(List publishedDocsConfiguration, List newProjects) {
        def publishProjectsNames = publishedDocsConfiguration.collect { it.project }
        return newProjects.sort { publishProjectsNames.indexOf(it.name) }
    }

    private def parseJsonText(String text) {
        if (!text) {
            return null
        }
        return new JsonSlurper().parseText(text)
    }

    @Timeout
    void update() {
        try {
            timer?.cancel()
        } catch (ignore) {
            // no-op
        }
        def newProjects = []
        Map<String, DtoContributor> newContributors = [:]
        Set<DtoContributions> newContributions = []
        def publishedDocsConfiguration = new Yaml().loadAll(
                http.loadGithubResource('tomitribe.io.config', 'master', 'published_docs.yaml')
        ).collect({ it })
        Map<String, Set<String>> publishedTagsMap = publishedDocsConfiguration.collectEntries {
            [(it.project), it.tags]
        }
        publishedTagsMap.keySet().each { String projectName ->
            def pageUrl = "https://api.github.com/repos/tomitribe/${projectName}"
            def json = parseJsonText(http.getUrlContentWithToken(pageUrl))
            if (!json) {
                logger.warning("${projectName} expected to be published but project does not exist.")
                return
            }
            Set<String> publishedTags = publishedTagsMap.get(json.name)
            if (!publishedTags) {
                logger.warning("${projectName} expected to be published but it has no publishable tag. Please check " +
                        "the https://github.com/tomitribe/tomitribe.io.config/blob/master/published_docs.yaml file.")
                return
            }
            List<String> tags = parseJsonText(
                    http.getUrlContentWithToken("https://api.github.com/repos/tomitribe/${json.name}/tags")
            ).collect({ it.name })
            tags.add(0, 'master') // all projects contain a master branch
            String release = tags.find({ publishedTags.contains(it) })
            if (!release) {
                logger.warning("${projectName} expected to be published but actual project does not contain any tag " +
                        "listed in the " +
                        "https://github.com/tomitribe/tomitribe.io.config/blob/master/published_docs.yaml file.")
                return
            }
            def snapshot = http.loadGithubResourceEncoded('tomitribe.io.config', 'master', "docs/${json.name}/snapshot.png")
            def icon = http.loadGithubResourceEncoded('tomitribe.io.config', 'master', "docs/${json.name}/icon.png")
            def longDescription = http.loadGithubResourceHtml('tomitribe.io.config', 'master', "docs/${json.name}/long_description.adoc")?.trim()
            def documentation = http.loadGithubResourceHtml('tomitribe.io.config', 'master', "docs/${json.name}/documentation.adoc")?.trim() ?:
                    http.loadGithubResourceHtml(json.name as String, release, 'README.adoc')?.trim()
            def shortDescription = (
                    http.loadGithubResource('tomitribe.io.config', 'master', "docs/${json.name}/short_description.txt")
                            ?: json.description)?.trim()
            if (!longDescription || !documentation || !shortDescription || !snapshot || !icon) {
                logger.warning("${projectName} expected to be published but it has missing data. " +
                        "is long description empty? ${!longDescription};" +
                        "is documentation empty? ${!documentation};" +
                        "is short description empty? ${!shortDescription};" +
                        "is snapshot empty? ${!snapshot};" +
                        "is icon empty? ${!icon};")
                return
            }
            def projectContributors = getContributors(json.name as String)
            projectContributors.each { data ->
                def projectContributor = data.contributor
                newContributors.put(projectContributor.name, projectContributor)
                newContributions << data.contributions
            }
            newProjects.add(new DtoProject(
                    name: json.name,
                    shortDescription: shortDescription,
                    longDescription: longDescription,
                    snapshot: snapshot,
                    icon: icon,
                    documentation: documentation,
                    contributors: projectContributors.collect { it.contributor },
                    tags: tags.findAll({ publishedTags.contains(it) })
            ))
        }
        this.projects = sortMyConfigFile(publishedDocsConfiguration, newProjects)
        this.contributors = newContributors.values()
        this.contributions = newContributions
        timer = timerService.createTimer(UPDATE_INTERVAL, 'Reload ServiceGithub')
    }

    List<DtoProject> getProjects() {
        projects
    }

    Set<DtoContributor> getContributors() {
        contributors
    }

    Set<DtoContributions> getContributions() {
        contributions
    }
}
