import org.junit.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

public class MethodHandleTest {
    @Test
    public void testName() throws Exception {

        Method meth = Foo.class.getDeclaredMethods()[0];

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle mh = lookup.unreflect(meth);

        Foo foo = new Foo();
        String myStr = "aaa";
        Integer myInt = new Integer(10);
        Object[] myArray = {foo, myStr, myInt};

        try {
            mh.invokeWithArguments(myArray); // throws Exception
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    class Foo {
    }

}
