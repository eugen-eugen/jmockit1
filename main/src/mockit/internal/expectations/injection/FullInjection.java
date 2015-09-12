/*
 * Copyright (c) 2006-2015 Rogério Liesenfeld
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.internal.expectations.injection;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.logging.*;
import javax.annotation.*;
import javax.inject.*;
import static java.lang.reflect.Modifier.*;

import mockit.internal.startup.*;
import static mockit.external.asm.Opcodes.*;
import static mockit.internal.expectations.injection.InjectionPoint.*;
import static mockit.internal.util.ConstructorReflection.*;

/**
 * Responsible for recursive injection of dependencies as requested by a {@code @Tested(fullyInitialized = true)} field.
 */
final class FullInjection
{
   private static final int INVALID_TYPES = ACC_ABSTRACT + ACC_ANNOTATION + ACC_ENUM;

   @Nonnull private final InjectionState injectionState;
   @Nullable private final JPADependencies jpaDependencies;

   FullInjection(@Nonnull InjectionState injectionState)
   {
      this.injectionState = injectionState;
      jpaDependencies = JPADependencies.createIfAvailableInClasspath(injectionState);
   }

   @Nullable
   Object newInstance(@Nonnull FieldInjection fieldInjection, @Nonnull Field fieldToBeInjected)
   {
      Class<?> fieldType = fieldToBeInjected.getType();

      if (fieldType == Logger.class) {
         return Logger.getLogger(fieldInjection.nameOfTestedClass);
      }

      if (!isInstantiableType(fieldType)) {
         return null;
      }

      Object dependencyKey = getDependencyKey(fieldToBeInjected);
      Object dependency = injectionState.getInstantiatedDependency(dependencyKey);

      if (dependency == null) {
         if (INJECT_CLASS != null && fieldType == Provider.class) {
            dependency = createProviderInstance(fieldToBeInjected, dependencyKey);
         }
         else {
            dependency = createNewInstance(fieldType, dependencyKey);

            if (dependency != null) {
               registerNewInstance(fieldInjection, dependencyKey, dependency);
            }
         }
      }

      return dependency;
   }

   private static boolean isInstantiableType(@Nonnull Class<?> type)
   {
      if (type.isPrimitive() || type.isArray() || type.isAnnotation()) {
         return false;
      }

      if (!type.isInterface()) {
         int typeModifiers = type.getModifiers();

         if ((typeModifiers & INVALID_TYPES) != 0 || !isStatic(typeModifiers) && type.isMemberClass()) {
            return false;
         }
      }

      return true;
   }

   @Nonnull
   private Object getDependencyKey(@Nonnull Field fieldToBeInjected)
   {
      Class<?> dependencyClass = fieldToBeInjected.getType();

      if (jpaDependencies != null) {
         for (Annotation annotation : fieldToBeInjected.getDeclaredAnnotations()) {
            String id = JPADependencies.getDependencyIdIfAvailable(annotation);

            if (id != null && !id.isEmpty()) {
               return dependencyClass.getName() + ':' + id;
            }
         }
      }

      return dependencyClass;
   }

   @Nonnull
   private Object createProviderInstance(@Nonnull Field fieldToBeInjected, @Nonnull final Object dependencyKey)
   {
      ParameterizedType genericType = (ParameterizedType) fieldToBeInjected.getGenericType();
      final Class<?> providedClass = (Class<?>) genericType.getActualTypeArguments()[0];

      if (providedClass.isAnnotationPresent(Singleton.class)) {
         return new Provider<Object>() {
            private Object dependency;

            @Override
            public synchronized Object get()
            {
               if (dependency == null) {
                  dependency = createNewInstance(providedClass, dependencyKey);
               }

               return dependency;
            }
         };
      }

      return new Provider<Object>() {
         @Override
         public Object get()
         {
            Object dependency = createNewInstance(providedClass, dependencyKey);
            return dependency;
         }
      };
   }

   @Nullable
   private Object createNewInstance(@Nonnull Class<?> dependencyClass, @Nonnull Object dependencyKey)
   {
      if (!dependencyClass.isInterface()) {
         return newInstanceUsingDefaultConstructorIfAvailable(dependencyClass);
      }

      if (jpaDependencies != null) {
         Object newInstance = jpaDependencies.newInstanceIfApplicable(dependencyClass, dependencyKey);

         if (newInstance != null) {
            return newInstance;
         }
      }

      Class<?> implementationClass = findImplementationClassInClasspathIfUnique(dependencyClass);

      if (implementationClass != null) {
         return newInstanceUsingDefaultConstructorIfAvailable(implementationClass);
      }

      return null;
   }

   @Nullable
   private static Class<?> findImplementationClassInClasspathIfUnique(@Nonnull Class<?> dependencyClass)
   {
      ClassLoader dependencyLoader = dependencyClass.getClassLoader();
      Class<?> implementationClass = null;

      if (dependencyLoader != null) {
         Class<?>[] loadedClasses = Startup.instrumentation().getInitiatedClasses(dependencyLoader);

         for (Class<?> loadedClass : loadedClasses) {
            if (loadedClass != dependencyClass && dependencyClass.isAssignableFrom(loadedClass)) {
               if (implementationClass != null) {
                  return null;
               }

               implementationClass = loadedClass;
            }
         }
      }

      return implementationClass;
   }

   private void registerNewInstance(
      @Nonnull FieldInjection fieldInjection, @Nonnull Object dependencyKey, @Nonnull Object dependency)
   {
      Class<?> instantiatedClass = dependency.getClass();

      if (fieldInjection.isClassFromSameModuleOrSystemAsTestedClass(instantiatedClass)) {
         fieldInjection.fillOutDependenciesRecursively(dependency);
         injectionState.lifecycleMethods.executePostConstructMethodIfAny(instantiatedClass, dependency);
      }

      injectionState.saveInstantiatedDependency(dependencyKey, dependency, false);
   }
}
