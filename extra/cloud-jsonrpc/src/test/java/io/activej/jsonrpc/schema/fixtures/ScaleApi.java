/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.jsonrpc.schema.fixtures;

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * A service of <b>500</b> methods, for {@code JsonRpcSchemaGeneratorAdversarialTest}'s scale row. <b>Machine
 * generated</b> — never edit a method by hand; regenerate the whole file, because three of its properties are
 * arithmetic over the whole list and a hand edit would quietly break one of them.
 *
 * <h2>Every wire name is engineered to break a sort that is only accidentally right</h2>
 * <ul>
 *     <li><b>200</b> names share one 56-character prefix —
 *     {@code scale.deeply.nested.namespace.with.a.long.common.prefix.} — and differ only in an
 *     <b>unpadded</b> decimal suffix, so lexicographic order and numeric order disagree everywhere:
 *     {@code method10} sorts before {@code method2}. A generator sorting numerically, or grouping by prefix
 *     and forgetting to order within the group, produces a different document.</li>
 *     <li>60 names differ from each other <b>only in case</b> ({@code Item3} / {@code item3} / {@code ITEM3}),
 *     so a case-insensitive comparator — a {@code Collator}, or {@code String.CASE_INSENSITIVE_ORDER} — makes
 *     the order locale- or JDK-dependent, which is exactly what rule M2's "natural String order" refuses.</li>
 *     <li>40 names cross the separator against the alphabet: {@code a0}, {@code a0.b}, {@code a0Z},
 *     {@code a0b} sort in <b>that</b> order because {@code '.'} (0x2e) &lt; {@code 'Z'} (0x5a) &lt;
 *     {@code 'b'} (0x62). Any sort that special-cases the dot as a namespace separator gets these wrong.</li>
 * </ul>
 *
 * <h2>The declaration order is the exact reverse of the emitted order</h2>
 * Java identifiers are {@code m000}…{@code m499} in <b>descending</b> wire-name order, and
 * {@code JsonRpcServiceContract} iterates by Java identifier. So the generator is handed this contract's
 * methods in precisely the worst order for rule M2 — a generator preserving discovery order emits the whole
 * document backwards, and one preserving nothing emits it in hash order.
 *
 * <p>Method shapes cycle so the scale row exercises the whole of rule M3/M4/M5 and not one shape 500 times:
 * every 11th is a notification (no {@code result} member), every 5th takes two named parameters, every 3rd
 * takes one unannotated parameter (positional, so {@code paramStructure} is present), the rest take none.
 */
@JsonRpcService("")
public interface ScaleApi {
	@JsonRpcNotification("scale.z99") void m000(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z98") Promise<String> m001();
	@JsonRpcMethod("scale.z97") Promise<String> m002();
	@JsonRpcMethod("scale.z96") Promise<String> m003(String unannotated);
	@JsonRpcMethod("scale.z95") Promise<String> m004();
	@JsonRpcMethod("scale.z94")
	Promise<Integer> m005(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z93") Promise<String> m006(String unannotated);
	@JsonRpcMethod("scale.z92") Promise<String> m007();
	@JsonRpcMethod("scale.z91") Promise<String> m008();
	@JsonRpcMethod("scale.z90") Promise<String> m009(String unannotated);
	@JsonRpcMethod("scale.z9")
	Promise<Integer> m010(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcNotification("scale.z89") void m011(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z88") Promise<String> m012(String unannotated);
	@JsonRpcMethod("scale.z87") Promise<String> m013();
	@JsonRpcMethod("scale.z86") Promise<String> m014();
	@JsonRpcMethod("scale.z85")
	Promise<Integer> m015(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z84") Promise<String> m016();
	@JsonRpcMethod("scale.z83") Promise<String> m017();
	@JsonRpcMethod("scale.z82") Promise<String> m018(String unannotated);
	@JsonRpcMethod("scale.z81") Promise<String> m019();
	@JsonRpcMethod("scale.z80")
	Promise<Integer> m020(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z8") Promise<String> m021(String unannotated);
	@JsonRpcNotification("scale.z79") void m022(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z78") Promise<String> m023();
	@JsonRpcMethod("scale.z77") Promise<String> m024(String unannotated);
	@JsonRpcMethod("scale.z76")
	Promise<Integer> m025(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z75") Promise<String> m026();
	@JsonRpcMethod("scale.z74") Promise<String> m027(String unannotated);
	@JsonRpcMethod("scale.z73") Promise<String> m028();
	@JsonRpcMethod("scale.z72") Promise<String> m029();
	@JsonRpcMethod("scale.z71")
	Promise<Integer> m030(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z70") Promise<String> m031();
	@JsonRpcMethod("scale.z7") Promise<String> m032();
	@JsonRpcNotification("scale.z69") void m033(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z68") Promise<String> m034();
	@JsonRpcMethod("scale.z67")
	Promise<Integer> m035(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z66") Promise<String> m036(String unannotated);
	@JsonRpcMethod("scale.z65") Promise<String> m037();
	@JsonRpcMethod("scale.z64") Promise<String> m038();
	@JsonRpcMethod("scale.z63") Promise<String> m039(String unannotated);
	@JsonRpcMethod("scale.z62")
	Promise<Integer> m040(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z61") Promise<String> m041();
	@JsonRpcMethod("scale.z60") Promise<String> m042(String unannotated);
	@JsonRpcMethod("scale.z6") Promise<String> m043();
	@JsonRpcNotification("scale.z59") void m044(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z58")
	Promise<Integer> m045(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z57") Promise<String> m046();
	@JsonRpcMethod("scale.z56") Promise<String> m047();
	@JsonRpcMethod("scale.z55") Promise<String> m048(String unannotated);
	@JsonRpcMethod("scale.z54") Promise<String> m049();
	@JsonRpcMethod("scale.z53")
	Promise<Integer> m050(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z52") Promise<String> m051(String unannotated);
	@JsonRpcMethod("scale.z51") Promise<String> m052();
	@JsonRpcMethod("scale.z50") Promise<String> m053();
	@JsonRpcMethod("scale.z5") Promise<String> m054(String unannotated);
	@JsonRpcNotification("scale.z49") void m055(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z48") Promise<String> m056();
	@JsonRpcMethod("scale.z47") Promise<String> m057(String unannotated);
	@JsonRpcMethod("scale.z46") Promise<String> m058();
	@JsonRpcMethod("scale.z45") Promise<String> m059();
	@JsonRpcMethod("scale.z44")
	Promise<Integer> m060(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z43") Promise<String> m061();
	@JsonRpcMethod("scale.z42") Promise<String> m062();
	@JsonRpcMethod("scale.z41") Promise<String> m063(String unannotated);
	@JsonRpcMethod("scale.z40") Promise<String> m064();
	@JsonRpcMethod("scale.z4")
	Promise<Integer> m065(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcNotification("scale.z39") void m066(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z38") Promise<String> m067();
	@JsonRpcMethod("scale.z37") Promise<String> m068();
	@JsonRpcMethod("scale.z36") Promise<String> m069(String unannotated);
	@JsonRpcMethod("scale.z35")
	Promise<Integer> m070(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z34") Promise<String> m071();
	@JsonRpcMethod("scale.z33") Promise<String> m072(String unannotated);
	@JsonRpcMethod("scale.z32") Promise<String> m073();
	@JsonRpcMethod("scale.z31") Promise<String> m074();
	@JsonRpcMethod("scale.z30")
	Promise<Integer> m075(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z3") Promise<String> m076();
	@JsonRpcNotification("scale.z29") void m077(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z28") Promise<String> m078(String unannotated);
	@JsonRpcMethod("scale.z27") Promise<String> m079();
	@JsonRpcMethod("scale.z26")
	Promise<Integer> m080(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z25") Promise<String> m081(String unannotated);
	@JsonRpcMethod("scale.z24") Promise<String> m082();
	@JsonRpcMethod("scale.z23") Promise<String> m083();
	@JsonRpcMethod("scale.z22") Promise<String> m084(String unannotated);
	@JsonRpcMethod("scale.z21")
	Promise<Integer> m085(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z20") Promise<String> m086();
	@JsonRpcMethod("scale.z2") Promise<String> m087(String unannotated);
	@JsonRpcNotification("scale.z199") void m088(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z198") Promise<String> m089();
	@JsonRpcMethod("scale.z197")
	Promise<Integer> m090(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z196") Promise<String> m091();
	@JsonRpcMethod("scale.z195") Promise<String> m092();
	@JsonRpcMethod("scale.z194") Promise<String> m093(String unannotated);
	@JsonRpcMethod("scale.z193") Promise<String> m094();
	@JsonRpcMethod("scale.z192")
	Promise<Integer> m095(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z191") Promise<String> m096(String unannotated);
	@JsonRpcMethod("scale.z190") Promise<String> m097();
	@JsonRpcMethod("scale.z19") Promise<String> m098();
	@JsonRpcNotification("scale.z189") void m099(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z188")
	Promise<Integer> m100(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z187") Promise<String> m101();
	@JsonRpcMethod("scale.z186") Promise<String> m102(String unannotated);
	@JsonRpcMethod("scale.z185") Promise<String> m103();
	@JsonRpcMethod("scale.z184") Promise<String> m104();
	@JsonRpcMethod("scale.z183")
	Promise<Integer> m105(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z182") Promise<String> m106();
	@JsonRpcMethod("scale.z181") Promise<String> m107();
	@JsonRpcMethod("scale.z180") Promise<String> m108(String unannotated);
	@JsonRpcMethod("scale.z18") Promise<String> m109();
	@JsonRpcNotification("scale.z179") void m110(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z178") Promise<String> m111(String unannotated);
	@JsonRpcMethod("scale.z177") Promise<String> m112();
	@JsonRpcMethod("scale.z176") Promise<String> m113();
	@JsonRpcMethod("scale.z175") Promise<String> m114(String unannotated);
	@JsonRpcMethod("scale.z174")
	Promise<Integer> m115(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z173") Promise<String> m116();
	@JsonRpcMethod("scale.z172") Promise<String> m117(String unannotated);
	@JsonRpcMethod("scale.z171") Promise<String> m118();
	@JsonRpcMethod("scale.z170") Promise<String> m119();
	@JsonRpcMethod("scale.z17")
	Promise<Integer> m120(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcNotification("scale.z169") void m121(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z168") Promise<String> m122();
	@JsonRpcMethod("scale.z167") Promise<String> m123(String unannotated);
	@JsonRpcMethod("scale.z166") Promise<String> m124();
	@JsonRpcMethod("scale.z165")
	Promise<Integer> m125(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z164") Promise<String> m126(String unannotated);
	@JsonRpcMethod("scale.z163") Promise<String> m127();
	@JsonRpcMethod("scale.z162") Promise<String> m128();
	@JsonRpcMethod("scale.z161") Promise<String> m129(String unannotated);
	@JsonRpcMethod("scale.z160")
	Promise<Integer> m130(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z16") Promise<String> m131();
	@JsonRpcNotification("scale.z159") void m132(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z158") Promise<String> m133();
	@JsonRpcMethod("scale.z157") Promise<String> m134();
	@JsonRpcMethod("scale.z156")
	Promise<Integer> m135(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z155") Promise<String> m136();
	@JsonRpcMethod("scale.z154") Promise<String> m137();
	@JsonRpcMethod("scale.z153") Promise<String> m138(String unannotated);
	@JsonRpcMethod("scale.z152") Promise<String> m139();
	@JsonRpcMethod("scale.z151")
	Promise<Integer> m140(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z150") Promise<String> m141(String unannotated);
	@JsonRpcMethod("scale.z15") Promise<String> m142();
	@JsonRpcNotification("scale.z149") void m143(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z148") Promise<String> m144(String unannotated);
	@JsonRpcMethod("scale.z147")
	Promise<Integer> m145(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z146") Promise<String> m146();
	@JsonRpcMethod("scale.z145") Promise<String> m147(String unannotated);
	@JsonRpcMethod("scale.z144") Promise<String> m148();
	@JsonRpcMethod("scale.z143") Promise<String> m149();
	@JsonRpcMethod("scale.z142")
	Promise<Integer> m150(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z141") Promise<String> m151();
	@JsonRpcMethod("scale.z140") Promise<String> m152();
	@JsonRpcMethod("scale.z14") Promise<String> m153(String unannotated);
	@JsonRpcNotification("scale.z139") void m154(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z138")
	Promise<Integer> m155(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z137") Promise<String> m156(String unannotated);
	@JsonRpcMethod("scale.z136") Promise<String> m157();
	@JsonRpcMethod("scale.z135") Promise<String> m158();
	@JsonRpcMethod("scale.z134") Promise<String> m159(String unannotated);
	@JsonRpcMethod("scale.z133")
	Promise<Integer> m160(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z132") Promise<String> m161();
	@JsonRpcMethod("scale.z131") Promise<String> m162(String unannotated);
	@JsonRpcMethod("scale.z130") Promise<String> m163();
	@JsonRpcMethod("scale.z13") Promise<String> m164();
	@JsonRpcNotification("scale.z129") void m165(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z128") Promise<String> m166();
	@JsonRpcMethod("scale.z127") Promise<String> m167();
	@JsonRpcMethod("scale.z126") Promise<String> m168(String unannotated);
	@JsonRpcMethod("scale.z125") Promise<String> m169();
	@JsonRpcMethod("scale.z124")
	Promise<Integer> m170(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z123") Promise<String> m171(String unannotated);
	@JsonRpcMethod("scale.z122") Promise<String> m172();
	@JsonRpcMethod("scale.z121") Promise<String> m173();
	@JsonRpcMethod("scale.z120") Promise<String> m174(String unannotated);
	@JsonRpcMethod("scale.z12")
	Promise<Integer> m175(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcNotification("scale.z119") void m176(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z118") Promise<String> m177(String unannotated);
	@JsonRpcMethod("scale.z117") Promise<String> m178();
	@JsonRpcMethod("scale.z116") Promise<String> m179();
	@JsonRpcMethod("scale.z115")
	Promise<Integer> m180(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z114") Promise<String> m181();
	@JsonRpcMethod("scale.z113") Promise<String> m182();
	@JsonRpcMethod("scale.z112") Promise<String> m183(String unannotated);
	@JsonRpcMethod("scale.z111") Promise<String> m184();
	@JsonRpcMethod("scale.z110")
	Promise<Integer> m185(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z11") Promise<String> m186(String unannotated);
	@JsonRpcNotification("scale.z109") void m187(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z108") Promise<String> m188();
	@JsonRpcMethod("scale.z107") Promise<String> m189(String unannotated);
	@JsonRpcMethod("scale.z106")
	Promise<Integer> m190(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z105") Promise<String> m191();
	@JsonRpcMethod("scale.z104") Promise<String> m192(String unannotated);
	@JsonRpcMethod("scale.z103") Promise<String> m193();
	@JsonRpcMethod("scale.z102") Promise<String> m194();
	@JsonRpcMethod("scale.z101")
	Promise<Integer> m195(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.z100") Promise<String> m196();
	@JsonRpcMethod("scale.z10") Promise<String> m197();
	@JsonRpcNotification("scale.z1") void m198(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.z0") Promise<String> m199();
	@JsonRpcMethod("scale.edge.a9b")
	Promise<Integer> m200(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.edge.a9Z") Promise<String> m201(String unannotated);
	@JsonRpcMethod("scale.edge.a9.b") Promise<String> m202();
	@JsonRpcMethod("scale.edge.a9") Promise<String> m203();
	@JsonRpcMethod("scale.edge.a8b") Promise<String> m204(String unannotated);
	@JsonRpcMethod("scale.edge.a8Z")
	Promise<Integer> m205(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.edge.a8.b") Promise<String> m206();
	@JsonRpcMethod("scale.edge.a8") Promise<String> m207(String unannotated);
	@JsonRpcMethod("scale.edge.a7b") Promise<String> m208();
	@JsonRpcNotification("scale.edge.a7Z") void m209(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.edge.a7.b")
	Promise<Integer> m210(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.edge.a7") Promise<String> m211();
	@JsonRpcMethod("scale.edge.a6b") Promise<String> m212();
	@JsonRpcMethod("scale.edge.a6Z") Promise<String> m213(String unannotated);
	@JsonRpcMethod("scale.edge.a6.b") Promise<String> m214();
	@JsonRpcMethod("scale.edge.a6")
	Promise<Integer> m215(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.edge.a5b") Promise<String> m216(String unannotated);
	@JsonRpcMethod("scale.edge.a5Z") Promise<String> m217();
	@JsonRpcMethod("scale.edge.a5.b") Promise<String> m218();
	@JsonRpcMethod("scale.edge.a5") Promise<String> m219(String unannotated);
	@JsonRpcNotification("scale.edge.a4b") void m220(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.edge.a4Z") Promise<String> m221();
	@JsonRpcMethod("scale.edge.a4.b") Promise<String> m222(String unannotated);
	@JsonRpcMethod("scale.edge.a4") Promise<String> m223();
	@JsonRpcMethod("scale.edge.a3b") Promise<String> m224();
	@JsonRpcMethod("scale.edge.a3Z")
	Promise<Integer> m225(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.edge.a3.b") Promise<String> m226();
	@JsonRpcMethod("scale.edge.a3") Promise<String> m227();
	@JsonRpcMethod("scale.edge.a2b") Promise<String> m228(String unannotated);
	@JsonRpcMethod("scale.edge.a2Z") Promise<String> m229();
	@JsonRpcMethod("scale.edge.a2.b")
	Promise<Integer> m230(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcNotification("scale.edge.a2") void m231(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.edge.a1b") Promise<String> m232();
	@JsonRpcMethod("scale.edge.a1Z") Promise<String> m233();
	@JsonRpcMethod("scale.edge.a1.b") Promise<String> m234(String unannotated);
	@JsonRpcMethod("scale.edge.a1")
	Promise<Integer> m235(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.edge.a0b") Promise<String> m236();
	@JsonRpcMethod("scale.edge.a0Z") Promise<String> m237(String unannotated);
	@JsonRpcMethod("scale.edge.a0.b") Promise<String> m238();
	@JsonRpcMethod("scale.edge.a0") Promise<String> m239();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method99")
	Promise<Integer> m240(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method98") Promise<String> m241();
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method97")
	void m242(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method96")
	Promise<String> m243(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method95") Promise<String> m244();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method94")
	Promise<Integer> m245(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method93")
	Promise<String> m246(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method92") Promise<String> m247();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method91") Promise<String> m248();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method90")
	Promise<String> m249(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method9")
	Promise<Integer> m250(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method89") Promise<String> m251();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method88")
	Promise<String> m252(String unannotated);
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method87")
	void m253(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method86") Promise<String> m254();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method85")
	Promise<Integer> m255(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method84") Promise<String> m256();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method83") Promise<String> m257();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method82")
	Promise<String> m258(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method81") Promise<String> m259();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method80")
	Promise<Integer> m260(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method8")
	Promise<String> m261(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method79") Promise<String> m262();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method78") Promise<String> m263();
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method77")
	void m264(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method76")
	Promise<Integer> m265(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method75") Promise<String> m266();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method74")
	Promise<String> m267(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method73") Promise<String> m268();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method72") Promise<String> m269();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method71")
	Promise<Integer> m270(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method70") Promise<String> m271();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method7") Promise<String> m272();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method69")
	Promise<String> m273(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method68") Promise<String> m274();
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method67")
	void m275(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method66")
	Promise<String> m276(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method65") Promise<String> m277();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method64") Promise<String> m278();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method63")
	Promise<String> m279(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method62")
	Promise<Integer> m280(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method61") Promise<String> m281();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method60")
	Promise<String> m282(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method6") Promise<String> m283();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method59") Promise<String> m284();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method58")
	Promise<Integer> m285(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method57")
	void m286(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method56") Promise<String> m287();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method55")
	Promise<String> m288(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method54") Promise<String> m289();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method53")
	Promise<Integer> m290(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method52")
	Promise<String> m291(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method51") Promise<String> m292();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method50") Promise<String> m293();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method5")
	Promise<String> m294(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method49")
	Promise<Integer> m295(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method48") Promise<String> m296();
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method47")
	void m297(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method46") Promise<String> m298();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method45") Promise<String> m299();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method44")
	Promise<Integer> m300(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method43") Promise<String> m301();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method42") Promise<String> m302();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method41")
	Promise<String> m303(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method40") Promise<String> m304();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method4")
	Promise<Integer> m305(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method39")
	Promise<String> m306(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method38") Promise<String> m307();
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method37")
	void m308(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method36")
	Promise<String> m309(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method35")
	Promise<Integer> m310(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method34") Promise<String> m311();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method33")
	Promise<String> m312(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method32") Promise<String> m313();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method31") Promise<String> m314();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method30")
	Promise<Integer> m315(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method3") Promise<String> m316();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method29") Promise<String> m317();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method28")
	Promise<String> m318(String unannotated);
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method27")
	void m319(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method26")
	Promise<Integer> m320(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method25")
	Promise<String> m321(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method24") Promise<String> m322();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method23") Promise<String> m323();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method22")
	Promise<String> m324(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method21")
	Promise<Integer> m325(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method20") Promise<String> m326();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method2")
	Promise<String> m327(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method199") Promise<String> m328();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method198") Promise<String> m329();
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method197")
	void m330(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method196") Promise<String> m331();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method195") Promise<String> m332();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method194")
	Promise<String> m333(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method193") Promise<String> m334();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method192")
	Promise<Integer> m335(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method191")
	Promise<String> m336(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method190") Promise<String> m337();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method19") Promise<String> m338();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method189")
	Promise<String> m339(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method188")
	Promise<Integer> m340(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method187")
	void m341(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method186")
	Promise<String> m342(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method185") Promise<String> m343();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method184") Promise<String> m344();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method183")
	Promise<Integer> m345(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method182") Promise<String> m346();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method181") Promise<String> m347();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method180")
	Promise<String> m348(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method18") Promise<String> m349();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method179")
	Promise<Integer> m350(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method178")
	Promise<String> m351(String unannotated);
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method177")
	void m352(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method176") Promise<String> m353();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method175")
	Promise<String> m354(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method174")
	Promise<Integer> m355(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method173") Promise<String> m356();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method172")
	Promise<String> m357(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method171") Promise<String> m358();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method170") Promise<String> m359();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method17")
	Promise<Integer> m360(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method169") Promise<String> m361();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method168") Promise<String> m362();
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method167")
	void m363(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method166") Promise<String> m364();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method165")
	Promise<Integer> m365(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method164")
	Promise<String> m366(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method163") Promise<String> m367();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method162") Promise<String> m368();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method161")
	Promise<String> m369(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method160")
	Promise<Integer> m370(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method16") Promise<String> m371();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method159")
	Promise<String> m372(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method158") Promise<String> m373();
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method157")
	void m374(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method156")
	Promise<Integer> m375(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method155") Promise<String> m376();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method154") Promise<String> m377();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method153")
	Promise<String> m378(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method152") Promise<String> m379();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method151")
	Promise<Integer> m380(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method150")
	Promise<String> m381(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method15") Promise<String> m382();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method149") Promise<String> m383();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method148")
	Promise<String> m384(String unannotated);
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method147")
	void m385(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method146") Promise<String> m386();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method145")
	Promise<String> m387(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method144") Promise<String> m388();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method143") Promise<String> m389();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method142")
	Promise<Integer> m390(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method141") Promise<String> m391();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method140") Promise<String> m392();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method14")
	Promise<String> m393(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method139") Promise<String> m394();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method138")
	Promise<Integer> m395(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method137")
	void m396(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method136") Promise<String> m397();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method135") Promise<String> m398();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method134")
	Promise<String> m399(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method133")
	Promise<Integer> m400(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method132") Promise<String> m401();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method131")
	Promise<String> m402(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method130") Promise<String> m403();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method13") Promise<String> m404();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method129")
	Promise<Integer> m405(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method128") Promise<String> m406();
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method127")
	void m407(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method126")
	Promise<String> m408(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method125") Promise<String> m409();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method124")
	Promise<Integer> m410(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method123")
	Promise<String> m411(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method122") Promise<String> m412();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method121") Promise<String> m413();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method120")
	Promise<String> m414(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method12")
	Promise<Integer> m415(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method119") Promise<String> m416();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method118")
	Promise<String> m417(String unannotated);
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method117")
	void m418(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method116") Promise<String> m419();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method115")
	Promise<Integer> m420(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method114") Promise<String> m421();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method113") Promise<String> m422();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method112")
	Promise<String> m423(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method111") Promise<String> m424();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method110")
	Promise<Integer> m425(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method11")
	Promise<String> m426(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method109") Promise<String> m427();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method108") Promise<String> m428();
	@JsonRpcNotification("scale.deeply.nested.namespace.with.a.long.common.prefix.method107")
	void m429(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method106")
	Promise<Integer> m430(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method105") Promise<String> m431();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method104")
	Promise<String> m432(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method103") Promise<String> m433();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method102") Promise<String> m434();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method101")
	Promise<Integer> m435(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method100") Promise<String> m436();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method10") Promise<String> m437();
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method1")
	Promise<String> m438(String unannotated);
	@JsonRpcMethod("scale.deeply.nested.namespace.with.a.long.common.prefix.method0") Promise<String> m439();
	@JsonRpcNotification("scale.case.item9") void m440(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.case.item8") Promise<String> m441(String unannotated);
	@JsonRpcMethod("scale.case.item7") Promise<String> m442();
	@JsonRpcMethod("scale.case.item6") Promise<String> m443();
	@JsonRpcMethod("scale.case.item5") Promise<String> m444(String unannotated);
	@JsonRpcMethod("scale.case.item4")
	Promise<Integer> m445(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.case.item3") Promise<String> m446();
	@JsonRpcMethod("scale.case.item2") Promise<String> m447(String unannotated);
	@JsonRpcMethod("scale.case.item19") Promise<String> m448();
	@JsonRpcMethod("scale.case.item18") Promise<String> m449();
	@JsonRpcMethod("scale.case.item17")
	Promise<Integer> m450(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcNotification("scale.case.item16") void m451(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.case.item15") Promise<String> m452();
	@JsonRpcMethod("scale.case.item14") Promise<String> m453(String unannotated);
	@JsonRpcMethod("scale.case.item13") Promise<String> m454();
	@JsonRpcMethod("scale.case.item12")
	Promise<Integer> m455(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.case.item11") Promise<String> m456(String unannotated);
	@JsonRpcMethod("scale.case.item10") Promise<String> m457();
	@JsonRpcMethod("scale.case.item1") Promise<String> m458();
	@JsonRpcMethod("scale.case.item0") Promise<String> m459(String unannotated);
	@JsonRpcMethod("scale.case.Item9")
	Promise<Integer> m460(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.case.Item8") Promise<String> m461();
	@JsonRpcNotification("scale.case.Item7") void m462(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.case.Item6") Promise<String> m463();
	@JsonRpcMethod("scale.case.Item5") Promise<String> m464();
	@JsonRpcMethod("scale.case.Item4")
	Promise<Integer> m465(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.case.Item3") Promise<String> m466();
	@JsonRpcMethod("scale.case.Item2") Promise<String> m467();
	@JsonRpcMethod("scale.case.Item19") Promise<String> m468(String unannotated);
	@JsonRpcMethod("scale.case.Item18") Promise<String> m469();
	@JsonRpcMethod("scale.case.Item17")
	Promise<Integer> m470(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.case.Item16") Promise<String> m471(String unannotated);
	@JsonRpcMethod("scale.case.Item15") Promise<String> m472();
	@JsonRpcNotification("scale.case.Item14") void m473(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.case.Item13") Promise<String> m474(String unannotated);
	@JsonRpcMethod("scale.case.Item12")
	Promise<Integer> m475(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.case.Item11") Promise<String> m476();
	@JsonRpcMethod("scale.case.Item10") Promise<String> m477(String unannotated);
	@JsonRpcMethod("scale.case.Item1") Promise<String> m478();
	@JsonRpcMethod("scale.case.Item0") Promise<String> m479();
	@JsonRpcMethod("scale.case.ITEM9")
	Promise<Integer> m480(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.case.ITEM8") Promise<String> m481();
	@JsonRpcMethod("scale.case.ITEM7") Promise<String> m482();
	@JsonRpcMethod("scale.case.ITEM6") Promise<String> m483(String unannotated);
	@JsonRpcNotification("scale.case.ITEM5") void m484(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.case.ITEM4")
	Promise<Integer> m485(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.case.ITEM3") Promise<String> m486(String unannotated);
	@JsonRpcMethod("scale.case.ITEM2") Promise<String> m487();
	@JsonRpcMethod("scale.case.ITEM19") Promise<String> m488();
	@JsonRpcMethod("scale.case.ITEM18") Promise<String> m489(String unannotated);
	@JsonRpcMethod("scale.case.ITEM17")
	Promise<Integer> m490(@JsonRpcParam("left") int left, @JsonRpcParam("right") int right);
	@JsonRpcMethod("scale.case.ITEM16") Promise<String> m491();
	@JsonRpcMethod("scale.case.ITEM15") Promise<String> m492(String unannotated);
	@JsonRpcMethod("scale.case.ITEM14") Promise<String> m493();
	@JsonRpcMethod("scale.case.ITEM13") Promise<String> m494();
	@JsonRpcNotification("scale.case.ITEM12") void m495(@JsonRpcParam("id") long id);
	@JsonRpcMethod("scale.case.ITEM11") Promise<String> m496();
	@JsonRpcMethod("scale.case.ITEM10") Promise<String> m497();
	@JsonRpcMethod("scale.case.ITEM1") Promise<String> m498(String unannotated);
	@JsonRpcMethod("scale.case.ITEM0") Promise<String> m499();
}
