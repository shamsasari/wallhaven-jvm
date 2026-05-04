module com.hibzahim.wallcycle {
    requires java.desktop;
    requires java.net.http;
    requires org.apache.logging.log4j;
    requires tools.jackson.databind;
    requires io.github.bucket4j.core;
    opens com.hibzahim.wallcycle to tools.jackson.databind;
}
