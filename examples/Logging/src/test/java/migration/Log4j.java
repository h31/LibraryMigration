package migration;

import org.apache.log4j.Logger;
import org.junit.Test;

/**
 * Created by artyom on 29.06.17.
 */
public class Log4j {
    private final Logger logger = Logger.getLogger(Log4j.class);

    @Test
    public void simpleLogging() {
        logger.info("Hello!");
    }
}
