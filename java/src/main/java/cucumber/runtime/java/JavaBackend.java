package cucumber.runtime.java;

import cucumber.annotation.After;
import cucumber.annotation.Before;
import cucumber.annotation.Order;
import cucumber.annotation.Pointcut;
import cucumber.fallback.runtime.java.DefaultJavaObjectFactory;
import cucumber.io.ClasspathResourceLoader;
import cucumber.io.ResourceLoader;
import cucumber.runtime.*;
import cucumber.runtime.snippets.SnippetGenerator;
import gherkin.formatter.model.Step;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

public class JavaBackend implements Backend {
    private final SnippetGenerator snippetGenerator = new SnippetGenerator(new JavaSnippet());
    private final ObjectFactory objectFactory;
    private final ClasspathResourceLoader classpathResourceLoader;
    private final ClasspathMethodScanner classpathMethodScanner;
    private Glue glue;
    private final List<JavaStepDefinition> steps = new ArrayList<JavaStepDefinition>();
    private final Map<Class<? extends Annotation>, List<JavaAdviceDefinition>> advices = new HashMap<Class<? extends Annotation>, List<JavaAdviceDefinition>>();

    public JavaBackend(ResourceLoader ignored) {
        classpathResourceLoader = new ClasspathResourceLoader(Thread.currentThread().getContextClassLoader());
        classpathMethodScanner = new ClasspathMethodScanner(classpathResourceLoader);
        objectFactory = loadObjectFactory();
    }

    public JavaBackend(ObjectFactory objectFactory) {
        classpathResourceLoader = new ClasspathResourceLoader(Thread.currentThread().getContextClassLoader());
        classpathMethodScanner = new ClasspathMethodScanner(classpathResourceLoader);
        this.objectFactory = objectFactory;
    }

    private ObjectFactory loadObjectFactory() {
        ObjectFactory objectFactory;
        try {
            objectFactory = classpathResourceLoader.instantiateExactlyOneSubclass(ObjectFactory.class, "cucumber.runtime", new Class[0], new Object[0]);
        } catch (CucumberException ce) {
            objectFactory = new DefaultJavaObjectFactory();
        }
        return objectFactory;
    }

    @Override
    public void loadGlue(Glue glue, List<String> gluePaths) {
        this.glue = glue;
        classpathMethodScanner.scan(this, gluePaths);
        applyAdvices();
    }

    /**
     * Convenience method for frameworks that wish to load glue from methods explicitly (possibly
     * found with a different mechanism than Cucumber's built-in classpath scanning).
     *
     * @param glue          where stepdefs and hooks will be added.
     * @param method        a candidate method.
     * @param glueCodeClass the class implementing the method. Must not be a subclass of the class implementing the method.
     */
    public void loadGlue(Glue glue, Method method, Class<?> glueCodeClass) {
        this.glue = glue;
        classpathMethodScanner.scan(this, method, glueCodeClass);
        applyAdvices();
    }

    private void applyAdvices() {
        for (JavaStepDefinition stepDefinition : steps) {
            Method method = stepDefinition.getMethod();
            Annotation[] annotations = method.getDeclaredAnnotations();
            for (Annotation annotation: annotations) {
                List<JavaAdviceDefinition> advices = this.advices.get(annotation.annotationType());
                if (advices != null) {
                    for (JavaAdviceDefinition advice : advices) {
                        glue.addStepDefinition(advice.advise(stepDefinition));
                    }
                }
            }
        }
    }

    @Override
    public void setUnreportedStepExecutor(UnreportedStepExecutor executor) {
        //Not used here yet
    }

    @Override
    public void buildWorld() {
        objectFactory.start();
    }

    @Override
    public void disposeWorld() {
        objectFactory.stop();
    }

    @Override
    public String getSnippet(Step step) {
        return snippetGenerator.getSnippet(step);
    }

    void addStepDefinition(Annotation annotation, Method method) {
        try {
            objectFactory.addClass(method.getDeclaringClass());
            JavaStepDefinition stepDefinition = new JavaStepDefinition(method, pattern(annotation), timeout(annotation), objectFactory);
            steps.add(stepDefinition);
            glue.addStepDefinition(stepDefinition);
        } catch (DuplicateStepDefinitionException e) {
            throw e;
        } catch (Throwable e) {
            throw new CucumberException(e);
        }
    }

    public void addAdviceDefinition(Annotation annotation, Method method) {
        try {
            objectFactory.addClass(method.getDeclaringClass());

            List<Class<? extends Annotation>> pointcuts = pointcuts(annotation);
            JavaAdviceDefinition adviceDefinition = new JavaAdviceDefinition(method, pattern(annotation), pointcuts, timeout(annotation), objectFactory);

            for (Class<? extends Annotation> pointcut : pointcuts) {
                List<JavaAdviceDefinition> existingAdvices = advices.get(pointcut);
                if (existingAdvices == null) {
                    existingAdvices = new ArrayList<JavaAdviceDefinition>();
                }
                existingAdvices.add(adviceDefinition);
                advices.put(pointcut, existingAdvices);
            }
        } catch (DuplicateStepDefinitionException e) {
            throw e;
        } catch (Throwable e) {
            throw new CucumberException(e);
        }
    }

    private Pattern pattern(Annotation annotation) throws Throwable {
        Method regexpMethod = annotation.getClass().getMethod("value");
        String regexpString = (String) Utils.invoke(annotation, regexpMethod, 0);
        return Pattern.compile(regexpString);
    }

    @SuppressWarnings("unchecked")
    private List<Class<? extends Annotation>> pointcuts(Annotation annotation) throws Throwable {
        Method method = annotation.getClass().getMethod("pointcuts");
        Class<? extends Annotation>[] pointcuts = (Class<? extends Annotation>[])Utils.invoke(annotation, method, 0);

        enforcePointcutAnnotation(pointcuts);

        return Arrays.asList(pointcuts);
    }

    private int timeout(Annotation annotation) throws Throwable {
        Method regexpMethod = annotation.getClass().getMethod("timeout");
        return (Integer) Utils.invoke(annotation, regexpMethod, 0);
    }

    private void enforcePointcutAnnotation(Class<? extends Annotation>[] pointcuts) {
        for (Class<? extends Annotation> pointcut : pointcuts) {
            if (pointcut.getAnnotation(Pointcut.class) == null) {
                throw new IllegalArgumentException(pointcut.getCanonicalName() + " is not a pointcut. (Not annotated with @Pointcut)");
            }
        }
    }

    void addHook(Annotation annotation, Method method) {
        objectFactory.addClass(method.getDeclaringClass());

        Order order = method.getAnnotation(Order.class);
        int hookOrder = (order == null) ? Integer.MAX_VALUE : order.value();

        if (annotation.annotationType().equals(Before.class)) {
            String[] tagExpressions = ((Before) annotation).value();
            int timeout = ((Before) annotation).timeout();
            glue.addBeforeHook(new JavaHookDefinition(method, tagExpressions, hookOrder, timeout, objectFactory));
        } else {
            String[] tagExpressions = ((After) annotation).value();
            int timeout = ((After) annotation).timeout();
            glue.addAfterHook(new JavaHookDefinition(method, tagExpressions, hookOrder, timeout, objectFactory));
        }
    }
}
