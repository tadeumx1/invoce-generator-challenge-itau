# Desafio Nota Fiscal

## Contexto

A aplicação atual processa notas fiscais, mas apresenta problemas de manutenção, testes, performance e consistência dos dados retornados.

O objetivo do desafio é corrigir os problemas existentes, melhorar a qualidade técnica da solução e propor uma arquitetura adequada para o fluxo completo.

## Problemas atuais

- Código difícil de manter e alterar.
- Regras de cálculo e fluxo de processamento complexos.
- Classe principal instável e com muitas responsabilidades.
- Baixa cobertura de testes, com alguns testes quebrando.
- A primeira execução funciona corretamente, mas as seguintes acumulam itens de execuções anteriores.
- Pedidos com mais de 6 itens ficam muito lentos.
- Após algumas execuções, o retorno também passa a ficar mais lento.
- Outros sistemas relatam inconsistências nos valores da nota e no total de itens.

## Premissas

- O payload de entrada não deve ser modificado.
- A aplicação usa Java 21 e Spring Boot 3.5.x.
- A solução deve corrigir os problemas funcionais e melhorar a manutenção do código.
- Boas práticas de desenvolvimento, testes, documentação e deploy devem ser consideradas.

## Execução local

```bash
./mvnw test
./mvnw verify
./mvnw spring-boot:run
```

O comando `./mvnw verify` é o gate principal: compila em Java 21, executa os testes,
valida formatação/estilo e gera o relatório JaCoCo em `target/site/jacoco/index.html`.

## Simulação de integração lenta

Existe no código uma lógica com `sleep` para simular uma integração externa mais lenta para itens a partir de determinado valor.

Essa lógica faz parte do desafio e **não deve ser simplesmente removida**.

O candidato deve analisar esse comportamento e propor uma solução para o problema.

## O que considerar na solução

O candidato deve considerar:

- Correção do acúmulo indevido de itens entre execuções.
- Correção das inconsistências nos valores da nota e total de itens.
- Melhoria da organização do código e separação de responsabilidades.
- Correção e criação de testes relevantes.
- Melhor tratamento para pedidos com mais de 6 itens.
- Uso adequado de Java 21 e Spring atualizado.
- Documentação mínima para executar, testar e entender a solução.
- Planejamento de deploy e operação da aplicação.
- Observabilidade, incluindo logs, métricas e tracing.
- Estratégias de resiliência para integrações lentas ou instáveis.

## Proposta de arquitetura

Além do código, o candidato deve apresentar uma proposta de desenho arquitetural do fluxo da aplicação.

A proposta deve considerar, quando aplicável:

- API Gateway;
- autenticação/autorização;
- APIs externas ou internas consumidas;
- tratamento de integrações lentas;
- filas ou processamento assíncrono;
- observabilidade;
- estratégia de deploy;
- contexto AWS.
- contexto AWS.
- contexto AWS.
