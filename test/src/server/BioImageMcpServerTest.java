package lab.kerrr.mcpbio.bioimageserver;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BioImageMcpServerTest {

    @Test
    void canInstantiateServer() {
        BioImageMcpServer server = new BioImageMcpServer();
        assertNotNull(server);
    }
}
