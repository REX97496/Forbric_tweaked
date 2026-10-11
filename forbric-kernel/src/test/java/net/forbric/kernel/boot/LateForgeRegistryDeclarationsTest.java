package net.forbric.kernel.boot;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class LateForgeRegistryDeclarationsTest {
	@Test void onlyNewListenerIdentitiesAreReplayedInTheirExistingOrder(){
		Object baseline=new String("same"),late=new String("same"),second=new Object();
		List<Object> result=LateForgeRegistryDeclarations.added(List.of(baseline),List.of(baseline,late,second,late));
		assertEquals(2,result.size());assertSame(late,result.get(0));assertSame(second,result.get(1));
	}
}
