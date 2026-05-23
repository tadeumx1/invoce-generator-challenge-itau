package br.com.itau.invoicegenerator.testsupport;

import br.com.itau.invoicegenerator.model.Address;
import br.com.itau.invoicegenerator.model.AddressPurpose;
import br.com.itau.invoicegenerator.model.Region;

public final class Addresses {

    private Addresses() {}

    public static Address entrega(Region region) {
        return address(AddressPurpose.ENTREGA, region);
    }

    public static Address cobrancaEntrega(Region region) {
        return address(AddressPurpose.COBRANCA_ENTREGA, region);
    }

    public static Address cobranca(Region region) {
        return address(AddressPurpose.COBRANCA, region);
    }

    public static Address entregaWithNullRegion() {
        return address(AddressPurpose.ENTREGA, null);
    }

    private static Address address(AddressPurpose purpose, Region region) {
        return Address.builder()
                .purpose(purpose)
                .region(region)
                .build();
    }
}
