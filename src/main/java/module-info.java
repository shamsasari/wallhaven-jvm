module com.hibzahim.wallcycle {
    requires java.desktop;
    requires java.management;
    requires java.net.http;
    requires org.apache.logging.log4j;
    requires tools.jackson.databind;
    opens com.hibzahim.wallcycle to tools.jackson.databind;
}
