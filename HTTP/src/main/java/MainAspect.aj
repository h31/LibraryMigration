/**
 * Created by artyom on 30.09.16.
 */
public aspect MainAspect {
    pointcut profiledOperation() : call(public * java.net.URL.*());

    before() : profiledOperation() {
        System.out.println(String.format("%s at position: %s:%d",
                thisJoinPoint.getSignature().getName(),
                thisJoinPoint.getSourceLocation().getFileName(),
                thisJoinPoint.getSourceLocation().getLine()));
    }
}