# Lightmap por pixel e textura contínua no mundo

Fork local do Iris 26.2 fornecido no arquivo `Iris-26.2.zip`.
Versão do mod: `1.11.4-pixel-lightmap.1+mc26.2`.

Há dois recursos independentes:

* **PIXEL_LIGHTING:** luz de blocos e céu amostrada por fragmento em um campo 3D
  compartilhado, com interpolação trilinear. O campo não depende dos triângulos,
  UVs das faces ou limites dos chunks.
* **WORLD_TEXTURE:** aplicação de uma imagem 2D personalizada em coordenadas do
  mundo, usando projeção triplanar. O padrão atravessa blocos e chunks contíguos,
  sem reiniciar em cada face. A imagem modula o material e pode colorir a luz final.

O shaderpack de exemplo combina ambos por padrão e permite desativar cada opção
na tela de opções do shaderpack. O carregador de texturas personalizadas existente
do Iris é reaproveitado; o novo volume contém os dados de iluminação do mundo.
Não é necessário criar uma imagem 3D para usar a textura de superfície.

## Instalação

1. Use Minecraft **26.2** com Fabric ou NeoForge, conforme o JAR escolhido.
2. Substitua o Iris original pelo JAR deste fork na pasta `mods`. Instale também
   o Sodium compatível com essa plataforma e essa versão do Minecraft
   (o projeto usa Sodium `0.9.2+mc26.2`).
3. Coloque `world-lightmap-demo.zip` na pasta `shaderpacks` e selecione-o no Iris.
   Para usar direto dos fontes, copie a pasta `examples/per-pixel-lightmap`.
4. Nas opções desse shaderpack, alterne `PIXEL_LIGHTING` e `WORLD_TEXTURE`.

Para personalizar a imagem, descompacte o shaderpack e substitua
`shaders/textures/world.png`. Preserve o arquivo `.mcmeta`, que ativa interpolação
e repetição. Uma imagem que combine nas próprias bordas evita emendas no período
de repetição. O período é de **16 blocos** por eixo no exemplo; pode ser alterado
na função `iris_sampleWorldTexture`, inclusive no módulo inteiro da câmera.

O exemplo é um demonstrador de terreno, sem efeitos cinematográficos. O Iris usa
os programas tradicionais para objetos não cobertos por esse shaderpack. A
projeção sobre faces com normais diferentes pode mudar de orientação; a continuidade
garantida pelo mapeamento é espacial entre superfícies coplanares contíguas, sem
reinício do padrão por bloco. Uma textura 3D personalizada pode substituir essa
projeção caso seja necessário um material volumétrico em todas as orientações.

## Integração em outro shaderpack

Adicione a `shaders.properties`:

```properties
iris.features.required = PER_PIXEL_LIGHTMAP
customTexture.worldLightmapTexture = textures/world.png
```

Copie `shaders/lib/world_lightmap.glsl` do exemplo para o shaderpack.
No fragment shader, inclua-o e use as funções separadamente:

```glsl
#include "/lib/world_lightmap.glsl"
vec2 uv = iris_samplePixelLightmap(relativePosition, worldNormal, lmcoord);
vec3 illumination = texture(lightmap, uv).rgb;
vec3 material = iris_sampleWorldTexture(relativePosition, worldNormal);
albedo.rgb *= illumination * material;
```

Também existe `iris_applyWorldLightmap(lightmap, relativePosition, worldNormal,
lmcoord)`, que devolve o produto dos dois componentes. Para utilizar apenas a
iluminação, use somente a primeira função; para utilizar apenas o padrão,
mantenha o lightmap tradicional e use somente a segunda função.

`relativePosition` é a posição do fragmento relativa à câmera, em eixos do mundo:
`(gbufferModelViewInverse * viewPosition).xyz`. Não some o uniform `cameraPosition`
antes de consultar o volume. Esse uniform do Iris pode sofrer rebasing.
Transforme a normal para eixos do mundo e normalize-a. Para shaders que deslocam
vértices, forneça a posição final do fragmento. O exemplo contém o vertex shader
completo e o caminho de descarte por `alphaTestRef`.

### Uniforms e dados novos

| Nome | Tipo | Significado |
| --- | --- | --- |
| `iris_lightVolume` | `sampler3D` | RGB8 normalizado; R = luz de bloco / 15, G = luz do céu / 15, B = máscara de chunk carregado |
| `iris_lightVolumeSize` | `int` | 64 amostras por eixo, uma por bloco |
| `iris_lightVolumeOrigin` | `vec3` | Canto mínimo do volume relativo à câmera real, calculado em precisão dupla |
| `iris_lightVolumeValid` | `int` | 1 quando o volume foi preenchido para o mundo atual; 0 solicita fallback |

O centro do texel `(i,j,k)` corresponde ao centro do bloco
`origin + (i,j,k) + 0.5`. Logo, a coordenada normalizada da posição `p` é
`(p - origin) / 64`. O helper desloca a consulta 0,501 bloco na direção da normal,
amostrando a região iluminada do lado exterior da superfície.

O volume usa filtro linear e clamp nos três eixos, sem mipmaps. Blocos de luz e
céu usam os valores do motor de luz do Minecraft. A máscara permite preservar
o lightmap original para chunks não carregados. Nos dois blocos próximos às
bordas do volume há uma transição suave para o lightmap original. O helper
converte os valores interpolados para UVs de lightmap: `(nível * 240 + 8) / 256`.

O sampler está disponível nos caminhos de terreno Sodium, gbuffers, shadow,
composite, final e compute que já registram os samplers de render targets.
Os uniforms são integrados ao sistema existente de uniforms do shaderpack.
Shaderpacks que não usam o sampler não alocam nem atualizam esse volume.
Eles mantêm o comportamento anterior. Não há reescrita automática de shaderpacks
existentes: cada pack precisa chamar o novo helper para receber o efeito.

## Atualização e custo

O volume cobre um cubo de 64 blocos em torno da câmera, aproximadamente 32 blocos
em cada direção. É preenchido novamente quando muda o tick do mundo, a câmera
atravessa um bloco ou o mundo muda. Assim, alterações de luz entram na atualização
seguinte; em um mundo pausado, sem mudança de tick ou câmera, a imagem é mantida.
As posições compartilhadas conservam o mesmo alinhamento após mover o volume.

São 262.144 amostras por atualização e 768 KiB de dados na CPU, mais a textura na
GPU. Esta primeira versão faz a atualização completa de maneira síncrona na
thread de renderização; o custo depende da máquina e ainda não foi medido em jogo.
Uma evolução baseada em seções de luz modificadas pode reduzir esse custo.
Os recursos GPU e o buffer nativo são liberados ao destruir/recarregar o pipeline.

Este recurso interpola a iluminação existente do Minecraft. Não implementa um
novo propagador de luz colorida, ray tracing, iluminação global ou resolução
sub-bloco física. A textura personalizada pode colorir o resultado, mas não
transforma uma fonte vanilla em propagação RGB. AO e cores de vértice do material
continuam sendo preservados pelo shader demonstrativo.

## Compilação e verificações

Use um JDK 25. Os comandos de compilação originais continuam válidos:

```sh
bash gradlew :fabric:build :neoforge:build -Pbuild.release=true
bash tools/check-lightmap.sh
```

O teste Java independente valida o layout completo de 64³ amostras, os canais e
a normalização, coordenadas negativas e do limite do mundo, recentralização,
continuidade nas bordas dos blocos e a máscara de amostras não carregadas.
Não depende de Minecraft nem de bibliotecas de teste.

Há também um teste opcional com contexto OpenGL real, por EGL:

```sh
python -m pip install PyOpenGL
python tests/check-shaders.py
```

Esse teste requer EGL/OpenGL compatível, como Mesa em Linux. Compila e liga as
quatro combinações das opções do shader de terreno e o programa final; verifica
a amostragem trilinear na GPU, recentralização, fallback, repetição sem emendas e
estabilidade da textura em coordenadas extremas. Não substitui um teste visual
no cliente Minecraft com seu hardware e seus mods.

Validação desta entrega (6 de novembro de 2026): builds Fabric e NeoForge
concluídos com sucesso; teste Java aprovado; teste GPU aprovado com Mesa 25.0.7,
OpenGL 4.5 Compatibility. Não foi executado um cliente Minecraft nesta validação.

## Licença

Mantém-se a licença LGPL do Iris e os arquivos de atribuição originais. Este fork
não é uma versão oficial do projeto IrisShaders. O histórico local parte do ZIP
fornecido, sem presumir um commit remoto específico de origem.
