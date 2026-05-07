import org.jspecify.annotations.NullMarked;

@NullMarked
module com.hibzahim.wallcycle {
    requires java.desktop;
    requires java.net.http;
    requires org.apache.logging.log4j;
    requires tools.jackson.databind;
    requires io.github.bucket4j.core;
    requires org.jspecify;
    opens com.hibzahim.wallcycle to tools.jackson.databind;
}
