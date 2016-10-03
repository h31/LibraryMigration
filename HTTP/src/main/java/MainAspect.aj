import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by artyom on 30.09.16.
 */
public aspect MainAspect {
    pointcut profiledOperation() : (call(public * *()) || call(*.new(..))) && within(Main);
    pointcut theEnd() : execution(public static void Main.main(*)) || (call(public static void System.exit()) && within(Main));

    before() : profiledOperation() {
//        System.out.println(String.format("%s at position: %s:%d",
//                thisJoinPoint.getSignature().getName(),
//                thisJoinPoint.getSourceLocation().getFileName(),
//                thisJoinPoint.getSourceLocation().getLine()));
        invocations.add(new Invocation(
                thisJoinPoint.getSignature().getName(),
                thisJoinPoint.getSourceLocation().getFileName(),
                thisJoinPoint.getSourceLocation().getLine(),
                thisJoinPoint.getSignature().getDeclaringTypeName()));
    }

    after() : theEnd() {
        System.out.println("The end. Instrumentation finished!");
        try {
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(new File("log.json"), invocations);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static class Invocation {
        public String name;
        public String filename;
        public int line;
        public String type;

        Invocation(String name, String filename, int line, String type) {
            this.name = name;
            this.filename = filename;
            this.line = line;
            this.type = type;
        }
    }

    public static List<Invocation> invocations = new ArrayList<>();
}