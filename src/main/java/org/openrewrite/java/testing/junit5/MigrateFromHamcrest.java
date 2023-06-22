/*
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.testing.junit5;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.util.ArrayList;
import java.util.List;

public class MigrateFromHamcrest extends Recipe {
    @Override
    public String getDisplayName() {
        return "Migrate from Hamcrest Matchers to JUnit5";
    }

    @Override
    public String getDescription() {
        return "This recipe will migrate all Hamcrest Matchers to JUnit5 assertions.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MigrationFromHamcrestVisitor();
    }

    private static class MigrationFromHamcrestVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext executionContext) {
            System.out.println("RECIPE RUN");
            J.MethodInvocation mi = super.visitMethodInvocation(method, executionContext);
            MethodMatcher matcherAssertTrue = new MethodMatcher("org.hamcrest.MatchAssert assertThat(String, boolean)");
            MethodMatcher matcherAssertMatcher = new MethodMatcher("org.hamcrest.MatcherAssert assertThat(..)");
            MethodMatcher matcherAssertMatcherWithReason = new MethodMatcher("org.hamcrest.MatcherAssert assertThat(String,*,org.hamcrest.Matcher)");

            if (matcherAssertTrue.matches(mi)) {
                //TODO simple
            } else if (matcherAssertMatcher.matches(mi)) {
                Expression hamcrestMatcher = mi.getArguments().get(1);
                if (hamcrestMatcher instanceof J.MethodInvocation) {
                    System.out.println("matched");
                    J.MethodInvocation matcherInvocation = (J.MethodInvocation)hamcrestMatcher;
                    maybeRemoveImport("org.hamcrest.Matchers." + matcherInvocation.getSimpleName());
                    maybeRemoveImport("org.hamcrest.MatcherAssert.assertThat");
                    String targetAssertion = getTranslatedAssert(matcherInvocation, false);
                    if (targetAssertion.equals("")) {
                        return mi;
                    }

                    JavaTemplate template = JavaTemplate.builder(getTemplateForMatcher(matcherInvocation,null, false))
                      .javaParser(JavaParser.fromJavaVersion().classpathFromResources(executionContext, "junit-jupiter-api-5.9"))
                      .staticImports("org.junit.jupiter.api.Assertions." + targetAssertion)
                      .build();

                    maybeAddImport("org.junit.jupiter.api.Assertions", targetAssertion);
                    return template.apply(getCursor(), method.getCoordinates().replace(),
                        getArgumentsForTemplate(matcherInvocation, null, mi.getArguments().get(0), false));
                }
                else throw new IllegalArgumentException("Parameter mismatch for " + mi + ".");
            }
            System.out.println("FINISH RUN");
            return mi;
        }

        private String getTranslatedAssert(J.MethodInvocation methodInvocation, boolean negated) {
            //to be replaced with a static map
            switch (methodInvocation.getSimpleName()) {
                case "equalTo":
                case "emptyArray":
                case "equalToIgnoringCase":
                case "hasEntry":
                case "hasSize":
                case "hasToString":
                    return negated ? "assertNotEquals" : "assertEquals";
                case "closeTo":
                case "containsString":
                case "empty":
                case "emptyCollectionOf":
                case "emptyIterable":
                case "emptyIterableOf":
                case "endsWith":
                case "greaterThan":
                case "greaterThanOrEqualTo":
                case "hasKey":
                case "hasValue":
                case "lessThan":
                case "lessThanOrEqualTo":
                case "sameInstance":
                case "startsWith":
                case "theInstance":
                case "isCompatibleWith":
                    return negated ? "assertFalse" : "assertTrue";
                case "instanceOf":
                case "isA":
                    return negated ? "assertFalse" : "assertInstanceOf";
                case "is":
                    if (methodInvocation.getArguments().get(0).toString().startsWith("org.hamcrest")) {
                        if (methodInvocation.getArguments().get(0) instanceof J.MethodInvocation) {
                            return getTranslatedAssert((J.MethodInvocation)methodInvocation.getArguments().get(0), negated);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else {
                        return negated ? "assertNotEquals" : "assertEquals";
                    }
                case "not":
                    if (methodInvocation.getArguments().get(0).toString().startsWith("org.hamcrest")) {
                        if (methodInvocation.getArguments().get(0) instanceof J.MethodInvocation) {
                            return getTranslatedAssert((J.MethodInvocation)methodInvocation.getArguments().get(0), !negated);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else {
                        return negated ? "assertEquals" : "assertNotEquals";
                    }
                case "notNullValue":
                    return negated ? "assertNull" : "assertNotNull";
                case "nullValue":
                    return negated ? "assertNotNull" : "assertNull";
            }
            return "";
        }

        private String getTemplateForMatcher(J.MethodInvocation matcher, Expression errorMsg, boolean negated) {
            StringBuilder sb = new StringBuilder();
            sb.append(getTranslatedAssert(matcher, negated));

            //to be replaced with a static map
            switch (matcher.getSimpleName()) {
                case "equalTo":
                    sb.append("(#{any(java.lang.Object)}, #{any(java.lang.Object)}");
                    break;
                case "closeTo":
                    sb.append("(Math.abs(#{any(java.lang.Object)} - #{any(java.lang.Object)}) < #{any(java.lang.Object)}");
                    break;
                case "containsString":
                    sb.append("(#{any(java.lang.Object)}.contains(#{any(java.lang.Object)}");
                    break;
                case "empty":
                    sb.append("(#{any(java.lang.Object)}.isEmpty()");
                    break;
                case "emptyArray":
                    sb.append("(0, #{any(java.lang.Object)}.length");
                    break;
                case "emptyCollectionOf":
                    sb.append("(#{any(java.lang.Object)}.isEmpty() && ");
                    sb.append("#{any(java.lang.Object)}.isAssignableFrom(#{any(java.lang.Object)}.getClass())");
                    break;
                case "emptyIterable":
                    sb.append("(#{any(java.lang.Object)}.iterator().hasNext()");
                    break;
                case "emptyIterableOf":
                    sb.append("(#{any(java.lang.Object)}.iterator().hasNext() && ");
                    sb.append("#{any(java.lang.Object)}.isAssignableFrom(#{any(java.lang.Object)}.getClass())");
                    break;
                case "endsWith":
                    sb.append("(#{any(java.lang.Object)}.substring(Math.abs(#{any(java.lang.Object)}.length() - #{any(java.lang.Object)}.length()))");
                    sb.append(".equals(#{any(java.lang.Object)})");
                    break;
                case "equalToIgnoringCase":
                    sb.append("(#{any(java.lang.String)}.toLowerCase(), (#{any(java.lang.String)}.toLowerCase()");
                    break;
                case "greaterThan":
                    sb.append("(#{any(java.lang.Object)} > #{any(java.lang.Object)}");
                    break;
                case "greaterThanOrEqualTo":
                    sb.append("(#{any(java.lang.Object)} >= #{any(java.lang.Object)}");
                    break;
                case "hasEntry":
                    if (matcher.getArguments().get(0).getType().toString().startsWith("org.hamcrest")) {
                        return "";
                    }
                    sb.append("(#{any(java.lang.Object)}, #{any(java.lang.Object)}.get(#{any(java.lang.Object)})");
                    break;
                case "hasKey":
                    if (matcher.getArguments().get(0).getType().toString().startsWith("org.hamcrest")) {
                        return "";
                    }
                    sb.append("(#{any(java.lang.Object)}.containsKey(#{any(java.lang.Object)})");
                    break;
                case "hasSize":
                    if (matcher.getArguments().get(0).getType().toString().startsWith("org.hamcrest")) {
                        return "";
                    }
                    sb.append("(#{any(java.lang.Object)}.size(), #{any(java.lang.Object)}");
                    break;
                case "hasToString":
                    if (matcher.getArguments().get(0).getType().toString().startsWith("org.hamcrest")) {
                        return "";
                    }
                    sb.append("(#{any(java.lang.Object)}.toString(), #{any(java.lang.Object)}");
                    break;
                case "hasValue":
                    if (matcher.getArguments().get(0).getType().toString().startsWith("org.hamcrest")) {
                        return "";
                    }
                    sb.append("(#{any(java.lang.Object)}.containsValue(#{any(java.lang.Object)})");
                    break;
                case "instanceOf":
                case "isA":
                    if (negated) {
                        sb.append("(#{any(java.lang.Object)} instanceof #{any(java.lang.Object)}");
                    } else {
                        sb.append("(#{any(java.lang.Object)}, #{any(java.lang.Object)}");
                    }
                    break;
                case "is":
                    if (matcher.getArguments().get(0).toString().startsWith("org.hamcrest")) {
                        if (matcher.getArguments().get(0) instanceof J.MethodInvocation) {
                            return getTemplateForMatcher((J.MethodInvocation) matcher.getArguments().get(0), errorMsg, negated);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else {
                        sb.append("(#{any(java.lang.Object)}, #{any(java.lang.Object)}");
                    }
                    break;
                case "lessThan":
                    sb.append("(#{any(java.lang.Object)} < #{any(java.lang.Object)}");
                    break;
                case "lessThanOrEqualTo":
                    sb.append("(#{any(java.lang.Object)} <= #{any(java.lang.Object)}");
                    break;
                case "not":
                    if (matcher.getArguments().get(0).toString().startsWith("org.hamcrest")) {
                        if (matcher.getArguments().get(0) instanceof J.MethodInvocation) {
                            return getTemplateForMatcher((J.MethodInvocation) matcher.getArguments().get(0), errorMsg, !negated);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else {
                        sb.append("(#{any(java.lang.Object)}, #{any(java.lang.Object)}");
                    }
                    break;
                case "notNullValue":
                case "nullValue":
                    sb.append("(#{any(java.lang.Object)}");
                    break;
                case "sameInstance":
                case "theInstance":
                    sb.append("(#{any(java.lang.Object)} == #{any(java.lang.Object)}");
                    break;
                case "startsWith":
                    sb.append("(#{any(java.lang.String)}.startsWith(#{any(java.lang.Object)})");
                    break;
                case "isCompatibleWith":
                    sb.append("(#{any(java.lang.String)}.isAssignableFrom(#{any(java.lang.String)}.getClass())");
                    break;
                default:
                    return "";
            }

            if (errorMsg != null) {
                sb.append(", #{any(java.lang.String)})");
            } else {
                sb.append(")");
            }
            return sb.toString();
        }

        private Object[] getArgumentsForTemplate(J.MethodInvocation matcher, Expression errorMsg, Expression examinedObj, boolean negated) {
            List<Expression> result = new ArrayList<>();

            switch (matcher.getSimpleName()) {
                case "equalTo":
                case "closeTo":
                case "containsString":
                case "equalToIgnoringCase":
                case "greaterThan":
                case "greaterThanOrEqualTo":
                case "hasKey":
                case "hasSize":
                case "hasToString":
                case "hasValue":
                case "lessThan":
                case "lessThanOrEqualTo":
                case "sameInstance":
                case "startsWith":
                case "theInstance":
                    result.add(examinedObj);
                    result.addAll(matcher.getArguments());
                    break;
                case "empty":
                case "emptyArray":
                case "emptyIterable":
                case "notNullValue":
                case "nullValue":
                    result.add(examinedObj);
                    break;
                case "emptyCollectionOf":
                case "emptyIterableOf":
                    result.add(examinedObj);
                    result.add(matcher.getArguments().get(0));
                    result.add(examinedObj);
                    break;
                case "endsWith":
                    result.add(examinedObj);
                    result.add(examinedObj);
                    result.add(matcher.getArguments().get(0));
                    result.add(matcher.getArguments().get(0));
                    break;
                case "hasEntry":
                    result.add(matcher.getArguments().get(1));
                    result.add(examinedObj);
                    result.add(matcher.getArguments().get(0));
                    break;
                case "instanceOf":
                case "isA":
                    if (negated) {
                        result.add(examinedObj);
                        result.add(matcher.getArguments().get(0));
                    } else {
                        result.add(matcher.getArguments().get(0));
                        result.add(examinedObj);
                    }
                    break;
                case "is":
                    if (matcher.getArguments().get(0).toString().startsWith("org.hamcrest")) {
                        if (matcher.getArguments().get(0) instanceof J.MethodInvocation) {
                            return getArgumentsForTemplate((J.MethodInvocation) matcher.getArguments().get(0), errorMsg, examinedObj, negated);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else {
                        result.add(examinedObj);
                        result.addAll(matcher.getArguments());
                    }
                    break;
                case "not":
                    if (matcher.getArguments().get(0).toString().startsWith("org.hamcrest")) {
                        if (matcher.getArguments().get(0) instanceof J.MethodInvocation) {
                            return getArgumentsForTemplate((J.MethodInvocation) matcher.getArguments().get(0), errorMsg, examinedObj, !negated);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } else {
                        result.add(examinedObj);
                        result.addAll(matcher.getArguments());
                    }
                    break;
                case "isCompatibleWith":
                    result.add(matcher.getArguments().get(0));
                    result.add(examinedObj);
                    break;
                default:
                    return new Object[]{};
            }

            if (errorMsg != null) result.add(errorMsg);

            return result.toArray();
        }
    }
}
