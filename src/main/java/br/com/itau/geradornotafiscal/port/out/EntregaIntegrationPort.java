package br.com.itau.geradornotafiscal.port.out;

import br.com.itau.geradornotafiscal.model.NotaFiscal;

public class EntregaIntegrationPort {
    public void criarAgendamentoEntrega(NotaFiscal notaFiscal) {

            try {
                //Simula o agendamento da entrega
                if(notaFiscal.getItens().size() > 5){
                    /* Aqui está o problema de performance do aplicacao para pedidos com mais de 5 itens
                        Esse if pode fugir a premissa de nao mexer no tempo da conexão assuma que foi uma falha na codificação do antigo desenvolvedor
                    * */
                    Thread.sleep(5000);
                }
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
    }
}
