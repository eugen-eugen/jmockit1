package mockit;



import org.junit.Test;
import org.junit.runner.RunWith;

import mockit.Injectable;
import mockit.auxclass.ABase;
import mockit.auxclass.ADao;
import mockit.integration.junit4.JMockit;









@RunWith(JMockit.class)
public class InjectableParameter2Test {
    @Test
    public void testSave(@Injectable final ADao<ABase> pbDao) {
        pbDao.getById();
    }
}
