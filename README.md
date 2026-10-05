# Companion Bridge 0.2.3 — tavallisen RuneLiten pluginin lähdeversio

Tämä on tarkistusta ja Plugin Hub -julkaisun valmistelua varten oleva lähdeprojekti.
Se ei vielä ole asennettavissa Plugin Hubista eikä sitä ole hyväksytty tai testattu
Windows-pelisessiossa. Käyttöönottoa ei siis vielä voi luvata toimivaksi.

Aiempi erillinen RuneLite-kehitysasiakas, BridgeLauncher ja `run`-tehtävä on
poistettu. Tässä versiossa ei ole peliasiakkaan käynnistintä, kirjautumistoimintoa
vaihtoehtoiselle asiakkaalle tai credentials.properties-ohjetta. Companionin
normaali käyttö ei tarvitse tämän projektin kääntämistä tai Java/Gradle-asennusta.

## Mitä kerääjä tekee

Kuuntelee tavallisen RuneLiten tapahtumia. Poiminta kirjataan vain, kun Take-
toiminto, ruudulta vähenevä esinemäärä ja inventoryyn kasvava määrä havaitaan
saman tai vierekkäisen pelitickin aikana ruudun läheisyydessä. Omistustieto
SELF/OTHER/GROUP/NONE erotellaan; vihollisen nimi on erikseen merkitty arvio.
Oma havaittu Drop-toiminto erotetaan omaksi maahanpudotukseksi.

Pluginilla ei ole omia verkkopyyntöjä, pelaamisen syötteitä, päivityslatauksia,
kirjautumistietojen lukua tai komentojen suorittamista. RuneLite-isäntäohjelmalla
on omat verkkotoimintonsa; tämä plugin ei rajoita muun RuneLiten käyttöoikeuksia.

Paikallinen vienti:
`%USERPROFILE%\.runelite\companion-bridge\HASH\pickups-YYYY-MM-DD.jsonl`.
Rivillä ovat hahmonimi, aika, tapahtumatunniste, esine, määrä, hinta, omistusluokka
ja arvioitu NPC-lähde. Ei muiden pelaajien nimiä tai pelin maailman/koordinaattien
vientiä. HASH jää hahmokohtaiseen kansiopolkuun. Lokit eivät ole salattuja.

Kirjoitusjono on 256 tapahtumaa, riviraja 64 KiB ja päiväloki 20 Mt. Hahmokohtaisen lokikansion yhteisraja on 100 Mt ja
2 500 tiedostoa. Yhteisrajan täyttyessä uusia tapahtumia ei tallenneta; vanhoja
lokeja ei poisteta. Siirrä vanhat lokit itse arkistoon, jos jatkat keruuta. Rajan tai
kirjoitusvirheen ylityksessä vienti ilmoittaa virheestä eikä pysäytä pelisäiettä.
Kirjoituspolun linkit tarkistetaan ja lopullinen tiedosto avataan NOFOLLOW-tilassa.
Tämä ei ole täydellinen Windows-sandbox tai suoja saman käyttäjän hyökkääjää vastaan.

## Rakennus ja testit (kehittäjälle)

JDK 17, Gradle 8.14.4 wrapper, RuneLite 1.13.1 käännösrajapinta, Java 11
luokkamuoto. `gradlew.bat test jar --dependency-verification strict --no-daemon`.
JAR sisältää vain pluginimme luokat; ei RuneLite-asiakasta, Logbackia, OkHttpia,
agenttia tai muita riippuvuus-JAR-tiedostoja. Manifesti on runelite-plugin.properties.

Rakennus-/testiketjun kirjastot on päivitetty, lukittu ja tarkistussummavarmennettu.
Gradlen SHA-256-metadatan ensimmäinen luottamus perustuu virallisten
repositorioiden HTTPS-latauksiin; summat eivät todista koodia virheettömäksi.
Normaali RuneLite tarjoaa ajossa oman kirjastokokoonpanonsa. Tämä projekti ei
korvaa tai päivitä sen riippuvuuksia.

## Rajat

- Havaintoihin perustuva seuranta ei ole palvelimen vahvistama poimintakuitti.
- Samanaikainen saman esineen alchaus/syönti tai toisen pelaajan poiminta voi
  peittää oman poiminnan tai johtaa virheelliseen havaintoon.
- Telegrab, automaattipoiminnat, säkkeihin suoraan siirtyvät tavarat ja
  alimaailmojen tapahtumat eivät kuulu tähän versioon.
- SELF ei todista omaa NPC-tappoa; OTHER ei todista toisen pelaajan NPC-tappoa.
- Aiemmat lokit voi lukea Companionissa ilman kerääjän ajoa.
- Farming-seurantaa ei vielä ole.

## Plugin Hub -valmistelu

Katso PLUGIN-HUB-VALMISTELU.md. Julkista repositorya, oikeaa commit-viittausta,
RuneLiten CI:tä ja ylläpitäjien tarkistusta ei ole vielä tehty. Niitä ei voi
korvata nimeämällä paikallinen JAR hyväksytyksi pluginiksi. Älä kopioi tätä JARia
satunnaisiin RuneLite-kansioihin tai käytä turvallisuustarkistusten ohituksia.

## Version 0.2.3 korjaukset

- Keruu on oletuksena pois päältä (`enabledByDefault=false`).
- Poimintayritys vanhenee myös inventory-/maahavaintoa käsiteltäessä.
  Taaksepäin siirtyvä tick tai inventoryn ristiriitainen väheneminen hylkää näyttöä.
- Poiminnan aikana muuttuva omistustieto merkitään tuntemattomaksi.
- Hahmonimi, esinenimi, lähdenimi ja hinnat validoidaan ennen lokijonoon lisäämistä.
- Maahavaintoja enintään 4 096, NPC-vihjeitä 256, omia pudotusvihjeitä 256 ja
  lähdenimiä kaksi per maahavainto. Kaksi eri lähdettä tarkoittaa epäselvää lähdettä.
- Lokit lukitaan kirjoituksen ajaksi. Vajaan viimeisen rivin perään ei kirjoiteta.
  Tallennettu rivi pakotetaan tiedostojärjestelmälle (`force(false)`). Tämä ei takaa
  tietojen säilymistä sähkökatkossa tai levyviassa.
- Linkit tarkistetaan myös kansioiden luonnin jälkeen; UNC/ADS/epänormalisoidut
  polut estetään. Ei suojaa saman käyttäjän aktiivista tiedostopolkujen vaihtoa vastaan.
- Sammutus tyhjentää hyväksytyn kirjoitusjonon taustalla. Uusi kirjoittaja ei
  käynnisty ennen edellisen päättymistä. Prosessin äkkikuolema voi silti katkaista rivin.

Siirrettävät Java-testit ja todelliset Windows-testit ovat erillisiä.
Windows-ohje: WINDOWS-TARKISTUS.md. Ajotulokset: TIETOTURVATARKISTUS-0.2.3.md.

Kesken jääneen lokin tapauksessa sammuta kerääjä ja siirrä kyseinen päiväloki
arkistoon. Writer ei poista tai korjaa käyttäjän lokeja automaattisesti.
Kaikki tähän mennessä onnistuneesti tallennetut kokonaiset rivit ovat edelleen luettavissa.
