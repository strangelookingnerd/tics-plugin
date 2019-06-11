Developing the "TICS Publisher" Jenkins plugin
==============================================

Getting started
---------------
-   Start Eclipse
-   Choose File -> Import -> Existing Maven project to import the JenkinsPlugin project
-   Right click project -> Maven -> Update Project
-   Right-click the project and choose Run As -> Maven Build
-   In "Goals" type hpi:run
-   Wait a long time while Eclipse is downloading dependencies and storing them in C:/Users/dreniers/m2 repository
-   Eventually a Jetty HTTP server should be running on "localhost:8080" or "localhost:8080/jenkins" serving Hudson/Jenkins
-   Go to http://localhost:8080/jenkins/pluginManager/installed : our plugin named "TICS Publisher" should be listed
-   However, you might see that although plugin is loaded, as it was listed in installed plugins, there is no post-build step available
    that is called "Publish TICS results".
    Cause: Eclipse by default does not do annotation processing (a JDK6 feature). The annotations @DataBoundConstructor and @Extension
    should be processed by the annotation processor, but these are not processed when Eclipse builds the java files.
    They _are_ processed when maven does the build.
-   If you did build using maven, but you do not see the TICS plugin, try an "mvn clean -e -X" followed by an "mvn compile hpi:run".
-   Disable automatic Eclipse building ("Project->Build automatically") and instead create a "Run Configuration", by right-clicking project and choosing "Run As" -> "Maven Build ...". Enter "compile hpi:run" as parameter.
-   Now each time you make changes to the code. 1) Stop the local Jenkins Jetty server in Eclipse's Debug panel (Ctrl+3 -> Debug). 2) Run the mvn compile hpi:run task you created (Alt+Shift+X -> M) and wait for about 10 seconds
-   Hint: for changes to Jelly files you do not have to restart Jenkins.


Deployment
----------
- Do not forget to update the version number in the build.gradle file   
- The plugin should automatically be published on the download site
- To deploy the plugin in Jenkins, go to "http://192.168.1.88:8080/pluginManager/advanced" and choose Upload Plugin.


Gradle-specific information
---------------------------
- Building the tics.hpi file can be done using the 'jpi' task
- Starting a local Jetty server can be done using the 'server' task
- The server task appears to hang at 83%, but it's actually running a server at port 8080 on localhost!
- Updating the version of the plugin is done automatically


Frequently Asked Questions
--------------------------
### I want to remove the link to my plugin in the left-hand sidebar

Return null in getIconName()

### What is the ${%Foo} syntax?

This is for internationalization

### I get "Caused by: java.lang.AssertionError: class hudson.plugins.tics.TicsPublisher is missing its descriptor"

Occurs when Eclipse builds the project, either due to "Build automatically" being enabled, or by doing a Clean from Eclipse. Disable it, do a Clean using Maven, and build again using Maven.

### I get an error while running mvn package during the tests "org.jvnet.hudson.test.JellyTestSuiteBuilder$JellyTestSuite(org.jvnet.hudson.test.junit.FailedTest)java.io.IOException: Failed to clean up temp dirs"

I tried suggestions from  http://stackoverflow.com/questions/20611211/jenkins-plugin-build-error, but this did not seem to work. In the end, I just disabled the tests by clicking the checkbox "Skip tests" in the Eclipse -> Run As -> Maven Build ...

### I want to add a dependency to a 3rd party library

Lookup the maven artifactId and groupId and add it to dependencies section of the POM, e.g.:

    <dependencies>
        <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.3.1</version>
        </dependency>
    </dependencies>

### How to get something on the right-hand on the project page?

Create a floatingBox.jelly for the project action. Note that this does not work for the build action.  

### How to reuse Jelly code?

Use the following syntax. The class name is looked up using Java's Class.forName(). 

    <st:include page="table.jelly" class="hudson.plugins.tics.TicsPublisher"/>

### When creating a new job in jenkins, I get an exception "Array index out of range: -1" at com.thoughtworks.xstream.core.util.OrderRetainingMap.entrySet(OrderRetainingMap.java:77)

On account of this https://issues.jenkins-ci.org/browse/JENKINS-19031 seems to be a problem in the XStream library:

    "XSTR-739 and XSTR-746: OrderRetainingMap fails if HashMap.putAll(Map) of Java Runtime is not implemented calling put for every element within the map."    

The reason that why it occurs now and not before is that we switched to Java8. The solution I took is to switch back to Java6, which is better anyway for users that do not have JRE8 yet. An alternative would have been to switch Jenkins version to 1.557.


### Eclipse gives a compiler errors, such as on getIconFileName(): "Must override a method"

Make sure you do a Maven Update by right-clicking the project and chosing Mave -> Update Project.


### I get "Exception in thread "main" java.lang.UnsupportedClassVersionError: org/apache/maven/cli/MavenCli : Unsupported major.minor version 51.0" when doing "mvn compile hpi:run"

I have no idea. Remove the "compile" target or building from the command line.



Useful reading
--------------
General plugin development:

- [https://wiki.jenkins-ci.org/display/JENKINS/Extend+Jenkins](https://wiki.jenkins-ci.org/display/JENKINS/Extend+Jenkins)
- [http://hudson-ci.org/docs/HudsonArch-View.pdf](http://hudson-ci.org/docs/HudsonArch-View.pdf)
- [http://javaadventure.blogspot.nl/2008/02/writing-hudson-plug-in-part-5-reporting.html](http://javaadventure.blogspot.nl/2008/02/writing-hudson-plug-in-part-5-reporting.html)
- [https://jenkins-ci.org/maven-site/jenkins-core/jelly-taglib-ref.html#form:block] Jelly tags
 

Example plugins:

- [Hello World plugin](https://github.com/jenkinsci/hello-world-plugin) (on which our plugin was based)
- [Clover Coverage](https://github.com/atlassian/clover-jenkins-plugin/blob/master/src/main/java/hudson/plugins/clover/CloverPublisher.java) (mentioned in ticket 14915)
- [Jacoco Coverage](https://github.com/jenkinsci/jacoco-plugin/tree/master/src/main/resources/hudson/plugins/jacoco)
- [Disk usage](https://github.com/jenkinsci/disk-usage-plugin/blob/master/src/main/resources/hudson/plugins/disk_usage/DiskUsagePlugin/index.jelly)


