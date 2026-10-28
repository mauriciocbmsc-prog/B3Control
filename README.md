# B3Control

Protótipo local para acompanhar editais de ensino do 9º BBM. Java 21, HTML, CSS, JavaScript sem framework e banco H2 2.3.232 em arquivo. O JavaScript implementa busca, filtros e painel lateral; o servidor e a persistência são Java.

## Executar

No checkout existente (não é necessário criar worktree):

```sh
cd /workspace/B3Control
./setup.sh
./run.sh
```

Requer Java 21 com suporte à execução de arquivos fonte, curl e sha256sum. `setup.sh` baixa o H2 do Maven Central com TLS e verifica SHA-256. O servidor usa a porta 8080 em loopback; `PORT=8081 ./run.sh` troca a porta. Abra o servidor no navegador apenas em um ambiente que permita acesso local. Não há hospedagem pública configurada.

Para popular um banco vazio com três exemplos fictícios: `./run.sh --seed`. Não duplica exemplos quando há registros. O banco preparado nesta sessão também inclui um cadastro demonstrativo de validação. Os exemplos não representam editais oficiais.

## Funcionalidades

- Dashboard com inscrições abertas, urgentes e encerradas.
- Prazo em vermelho até 2 dias, amarelo de 3 a 7 e verde acima de 7. Encerrados em cinza. O dia limite conta como aberto até o fim do dia em America/Sao_Paulo; editais com horário limite precisam de evolução do modelo.
- Pesquisa por curso, sigla/processo e município.
- Detalhes em painel lateral: local, período, carga horária, vagas, pré-requisitos e observações.
- Cadastro manual persistente com validação no servidor. Vagas e carga horária devem ser positivas; inscrição não pode encerrar depois do início do curso.

## Dados e operação

`data/b3control.mv.db` armazena os cadastros e fica fora do Git, assim como dependências. Para backup, pare o servidor e copie esse arquivo; não remova o banco com o serviço em execução. Reinícios preservam registros. O esquema contém oportunidades, contas e inscrições; as contas associam o perfil e a circunscrição ao bombeiro.

As planilhas PGE e BD Cursos orientaram os campos. Nenhum dado pessoal da planilha analítica foi importado. Os anexos originais ficam fora do checkout e não são necessários para iniciar o aplicativo.

## Perfis e fluxo de seleção

O acesso usa login e senha, com permissões verificadas no servidor:

- **Bombeiro:** inscreve a si mesmo enquanto o edital está aberto e acompanha sua candidatura. Não cadastra editais ou usuários nem consulta a lista dos demais candidatos.
- **Comandante:** vê candidatos apenas de sua circunscrição, aprova/rejeita voluntários, ordena prioridades por arraste ou setas e indica bombeiros da mesma circunscrição mesmo sem voluntariado. Indicações já entram aprovadas. Sua prioridade é local à circunscrição e não determina automaticamente as vagas finais.
- **B3:** cadastra editais e usuários, consulta todas as listas e define os contemplados entre candidatos aprovados, respeitando o total de vagas. Depois do fim das inscrições, salva as vagas e pode publicar os nomes e circunscrições no dashboard público.

Inscrições voluntárias e indicações encerram no prazo do edital. Comandantes podem analisar e reordenar depois desse prazo até a publicação. Mudanças nas decisões invalidam uma seleção final já salva e exigem nova definição pelo B3. Publicar exige seleção salva e edital encerrado, e bloqueia alterações posteriores nesta versão. Não há envio de e-mail automático: o B3 acompanha as listas pelo próprio sistema.

### Primeiro acesso e cadastro de usuários

Nenhuma senha padrão é criada. Para criar a primeira conta B3 em um terminal Bash, escolha uma senha de pelo menos 12 caracteres sem exibi-la:

```sh
cd /workspace/B3Control
read -rs -p 'Senha inicial B3: ' B3_ADMIN_PASSWORD
printf '\n'
export B3_ADMIN_PASSWORD
./run.sh
# Ao parar o servidor, limpe a variável:
unset B3_ADMIN_PASSWORD
```

O login inicial é `b3`. A variável só é usada se ainda não existe uma conta B3 no banco; não redefine senhas de contas existentes. Não salve senhas no Git, em scripts ou neste README. Após entrar, use **Gerenciar usuários** para cadastrar os bombeiros, comandantes e outros usuários B3. Informe a mesma circunscrição no cadastro do comandante e de seus bombeiros (por exemplo, `1ª CBM`). Nesta versão, cada conta pertence a uma circunscrição, e dois comandantes da mesma circunscrição acessam a mesma lista.

As senhas são armazenadas como hashes PBKDF2-HMAC-SHA256 com salt aleatório e 210.000 iterações. Sessões expiram em oito horas, são mantidas em memória e encerram após reinício. Cookies usam HttpOnly/SameSite=Strict; operações autenticadas exigem token CSRF. Há limite de 10 tentativas malsucedidas de login por conta em 15 minutos. O cookie não usa Secure porque o protótipo só atende HTTP em loopback; uma implantação externa precisa de HTTPS e adaptação do cookie.

O banco existente recebe tabelas de contas/inscrições e novas colunas por migração aditiva na inicialização, preservando os editais anteriores. `B3_DB_PATH` permite usar outro arquivo de banco; os testes usam um caminho temporário, sem alterar o banco de desenvolvimento.

## Limites e próximos passos

Cadastro de editais manual nesta etapa. Recebimento de e-mail, análise de PDF, anexos, edição/exclusão de editais, alteração/recuperação de senha, desativação de contas, auditoria e retificação de resultados publicados ainda não estão implementados. A vinculação de e-mail deverá usar credenciais em configuração segura e revisão dos dados extraídos antes da publicação do edital. A aplicação continua sendo um protótipo local; não há implantação pública configurada.

## Validação

```sh
./setup.sh
python tests/integration.py
node --check web/app.js
# Opcional: requer Playwright e Chromium instalado.
node tests/browser.cjs
```

A suíte HTTP cria um banco temporário e testa isolamento de perfis/circunscrições, inscrição voluntária e indicação, prevenção de duplicidade, aprovação/rejeição, ordenação, limite de vagas, fechamento do prazo, publicação, bloqueio posterior, logout, CSRF, limite de tentativas e persistência após reinício. O teste em navegador valida login, cadastros, inscrição, decisão do comandante, arraste/setas, pesquisa e layout móvel. Playwright é disponibilizado pelo ambiente e não é dependência de execução do site; `CHROMIUM_PATH` permite selecionar outro executável do navegador.

## Demonstração em três abas, sem login

A página inicial abre nas abas **Bombeiro**, **Comandante** e **B3**, com participantes e editais fictícios. É possível simular inscrições, decisões, ordenação, indicações, cadastro de edital e seleção/publicação sem criar contas. O edital demonstrativo de Salvamento em Altura já está encerrado para testar as vagas definitivas. As abas compartilham os dados da simulação em memória; atualizar a página reinicia os exemplos. Nenhuma ação desse modo chama a API ou grava no banco.

O botão **Acessar sistema com login** mantém disponível o fluxo autenticado com dados reais. **Voltar à demonstração** retorna às abas. As permissões do servidor permanecem obrigatórias no sistema real. Para validar a demonstração no navegador: `node tests/demo.cjs`.
