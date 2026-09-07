package cucumber.dcbstore.inmemdcbstore

import io.cucumber.core.options.Constants.{FILTER_TAGS_PROPERTY_NAME, GLUE_PROPERTY_NAME, PLUGIN_PROPERTY_NAME}
import org.junit.platform.suite.api.{ConfigurationParameter, IncludeEngines, SelectClasspathResource, Suite}

// https://docs.junit.org/current/api/org.junit.platform.suite.api/org/junit/platform/suite/api/package-summary.html
@Suite
@IncludeEngines(Array("cucumber"))
// DcbStore.feature lives in the dcb-store-testkit artifact. It is selected by classpath RESOURCE
// rather than by package so that the same form keeps working even if the testkit arrives as a jar,
// because Cucumber package scanning for .feature resources inside jars is unreliable.
@SelectClasspathResource("cucumber/dcbstore/DcbStore.feature")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @ignore")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "cucumber.dcbstore.inmemdcbstore")
// https://cucumber.io/docs/cucumber/reporting/#built-in-reporter-plugins
// https://github.com/cucumber/pretty-formatter?tab=readme-ov-file#test-outcome-coloring
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, summary, html:target/dcbstore-inmemdcbstore-test-report.html")
class InMemDcbStoreCucumberSpec
