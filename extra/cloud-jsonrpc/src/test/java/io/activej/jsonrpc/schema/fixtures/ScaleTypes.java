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

/**
 * The three shapes {@code JsonSchemaMappingAdversarialTest}'s scale rows need, at sizes no hand-written
 * fixture reaches. <b>Machine generated</b> — regenerate rather than edit, for the same reason as
 * {@link ScaleApi}: every property asserted about these is arithmetic over the whole declaration.
 *
 * <p>All three stay strictly <b>inside</b> the pinned subset, which is the point: a scale fixture that fell
 * back would prove nothing about scale, only about the fallback.
 */
public final class ScaleTypes {
	private ScaleTypes() {}

	/**
	 * An enum of {@value #CONSTANT_COUNT} constants declared in <b>descending</b> numeric order, so the
	 * emitted {@code enum} array is demonstrably declaration order and not a sort, a hash order or
	 * {@code values()} re-ordered by anything.
	 */
	public enum Thousand {
		C0999, C0998, C0997, C0996, C0995, C0994, C0993, C0992, C0991, C0990,
		C0989, C0988, C0987, C0986, C0985, C0984, C0983, C0982, C0981, C0980,
		C0979, C0978, C0977, C0976, C0975, C0974, C0973, C0972, C0971, C0970,
		C0969, C0968, C0967, C0966, C0965, C0964, C0963, C0962, C0961, C0960,
		C0959, C0958, C0957, C0956, C0955, C0954, C0953, C0952, C0951, C0950,
		C0949, C0948, C0947, C0946, C0945, C0944, C0943, C0942, C0941, C0940,
		C0939, C0938, C0937, C0936, C0935, C0934, C0933, C0932, C0931, C0930,
		C0929, C0928, C0927, C0926, C0925, C0924, C0923, C0922, C0921, C0920,
		C0919, C0918, C0917, C0916, C0915, C0914, C0913, C0912, C0911, C0910,
		C0909, C0908, C0907, C0906, C0905, C0904, C0903, C0902, C0901, C0900,
		C0899, C0898, C0897, C0896, C0895, C0894, C0893, C0892, C0891, C0890,
		C0889, C0888, C0887, C0886, C0885, C0884, C0883, C0882, C0881, C0880,
		C0879, C0878, C0877, C0876, C0875, C0874, C0873, C0872, C0871, C0870,
		C0869, C0868, C0867, C0866, C0865, C0864, C0863, C0862, C0861, C0860,
		C0859, C0858, C0857, C0856, C0855, C0854, C0853, C0852, C0851, C0850,
		C0849, C0848, C0847, C0846, C0845, C0844, C0843, C0842, C0841, C0840,
		C0839, C0838, C0837, C0836, C0835, C0834, C0833, C0832, C0831, C0830,
		C0829, C0828, C0827, C0826, C0825, C0824, C0823, C0822, C0821, C0820,
		C0819, C0818, C0817, C0816, C0815, C0814, C0813, C0812, C0811, C0810,
		C0809, C0808, C0807, C0806, C0805, C0804, C0803, C0802, C0801, C0800,
		C0799, C0798, C0797, C0796, C0795, C0794, C0793, C0792, C0791, C0790,
		C0789, C0788, C0787, C0786, C0785, C0784, C0783, C0782, C0781, C0780,
		C0779, C0778, C0777, C0776, C0775, C0774, C0773, C0772, C0771, C0770,
		C0769, C0768, C0767, C0766, C0765, C0764, C0763, C0762, C0761, C0760,
		C0759, C0758, C0757, C0756, C0755, C0754, C0753, C0752, C0751, C0750,
		C0749, C0748, C0747, C0746, C0745, C0744, C0743, C0742, C0741, C0740,
		C0739, C0738, C0737, C0736, C0735, C0734, C0733, C0732, C0731, C0730,
		C0729, C0728, C0727, C0726, C0725, C0724, C0723, C0722, C0721, C0720,
		C0719, C0718, C0717, C0716, C0715, C0714, C0713, C0712, C0711, C0710,
		C0709, C0708, C0707, C0706, C0705, C0704, C0703, C0702, C0701, C0700,
		C0699, C0698, C0697, C0696, C0695, C0694, C0693, C0692, C0691, C0690,
		C0689, C0688, C0687, C0686, C0685, C0684, C0683, C0682, C0681, C0680,
		C0679, C0678, C0677, C0676, C0675, C0674, C0673, C0672, C0671, C0670,
		C0669, C0668, C0667, C0666, C0665, C0664, C0663, C0662, C0661, C0660,
		C0659, C0658, C0657, C0656, C0655, C0654, C0653, C0652, C0651, C0650,
		C0649, C0648, C0647, C0646, C0645, C0644, C0643, C0642, C0641, C0640,
		C0639, C0638, C0637, C0636, C0635, C0634, C0633, C0632, C0631, C0630,
		C0629, C0628, C0627, C0626, C0625, C0624, C0623, C0622, C0621, C0620,
		C0619, C0618, C0617, C0616, C0615, C0614, C0613, C0612, C0611, C0610,
		C0609, C0608, C0607, C0606, C0605, C0604, C0603, C0602, C0601, C0600,
		C0599, C0598, C0597, C0596, C0595, C0594, C0593, C0592, C0591, C0590,
		C0589, C0588, C0587, C0586, C0585, C0584, C0583, C0582, C0581, C0580,
		C0579, C0578, C0577, C0576, C0575, C0574, C0573, C0572, C0571, C0570,
		C0569, C0568, C0567, C0566, C0565, C0564, C0563, C0562, C0561, C0560,
		C0559, C0558, C0557, C0556, C0555, C0554, C0553, C0552, C0551, C0550,
		C0549, C0548, C0547, C0546, C0545, C0544, C0543, C0542, C0541, C0540,
		C0539, C0538, C0537, C0536, C0535, C0534, C0533, C0532, C0531, C0530,
		C0529, C0528, C0527, C0526, C0525, C0524, C0523, C0522, C0521, C0520,
		C0519, C0518, C0517, C0516, C0515, C0514, C0513, C0512, C0511, C0510,
		C0509, C0508, C0507, C0506, C0505, C0504, C0503, C0502, C0501, C0500,
		C0499, C0498, C0497, C0496, C0495, C0494, C0493, C0492, C0491, C0490,
		C0489, C0488, C0487, C0486, C0485, C0484, C0483, C0482, C0481, C0480,
		C0479, C0478, C0477, C0476, C0475, C0474, C0473, C0472, C0471, C0470,
		C0469, C0468, C0467, C0466, C0465, C0464, C0463, C0462, C0461, C0460,
		C0459, C0458, C0457, C0456, C0455, C0454, C0453, C0452, C0451, C0450,
		C0449, C0448, C0447, C0446, C0445, C0444, C0443, C0442, C0441, C0440,
		C0439, C0438, C0437, C0436, C0435, C0434, C0433, C0432, C0431, C0430,
		C0429, C0428, C0427, C0426, C0425, C0424, C0423, C0422, C0421, C0420,
		C0419, C0418, C0417, C0416, C0415, C0414, C0413, C0412, C0411, C0410,
		C0409, C0408, C0407, C0406, C0405, C0404, C0403, C0402, C0401, C0400,
		C0399, C0398, C0397, C0396, C0395, C0394, C0393, C0392, C0391, C0390,
		C0389, C0388, C0387, C0386, C0385, C0384, C0383, C0382, C0381, C0380,
		C0379, C0378, C0377, C0376, C0375, C0374, C0373, C0372, C0371, C0370,
		C0369, C0368, C0367, C0366, C0365, C0364, C0363, C0362, C0361, C0360,
		C0359, C0358, C0357, C0356, C0355, C0354, C0353, C0352, C0351, C0350,
		C0349, C0348, C0347, C0346, C0345, C0344, C0343, C0342, C0341, C0340,
		C0339, C0338, C0337, C0336, C0335, C0334, C0333, C0332, C0331, C0330,
		C0329, C0328, C0327, C0326, C0325, C0324, C0323, C0322, C0321, C0320,
		C0319, C0318, C0317, C0316, C0315, C0314, C0313, C0312, C0311, C0310,
		C0309, C0308, C0307, C0306, C0305, C0304, C0303, C0302, C0301, C0300,
		C0299, C0298, C0297, C0296, C0295, C0294, C0293, C0292, C0291, C0290,
		C0289, C0288, C0287, C0286, C0285, C0284, C0283, C0282, C0281, C0280,
		C0279, C0278, C0277, C0276, C0275, C0274, C0273, C0272, C0271, C0270,
		C0269, C0268, C0267, C0266, C0265, C0264, C0263, C0262, C0261, C0260,
		C0259, C0258, C0257, C0256, C0255, C0254, C0253, C0252, C0251, C0250,
		C0249, C0248, C0247, C0246, C0245, C0244, C0243, C0242, C0241, C0240,
		C0239, C0238, C0237, C0236, C0235, C0234, C0233, C0232, C0231, C0230,
		C0229, C0228, C0227, C0226, C0225, C0224, C0223, C0222, C0221, C0220,
		C0219, C0218, C0217, C0216, C0215, C0214, C0213, C0212, C0211, C0210,
		C0209, C0208, C0207, C0206, C0205, C0204, C0203, C0202, C0201, C0200,
		C0199, C0198, C0197, C0196, C0195, C0194, C0193, C0192, C0191, C0190,
		C0189, C0188, C0187, C0186, C0185, C0184, C0183, C0182, C0181, C0180,
		C0179, C0178, C0177, C0176, C0175, C0174, C0173, C0172, C0171, C0170,
		C0169, C0168, C0167, C0166, C0165, C0164, C0163, C0162, C0161, C0160,
		C0159, C0158, C0157, C0156, C0155, C0154, C0153, C0152, C0151, C0150,
		C0149, C0148, C0147, C0146, C0145, C0144, C0143, C0142, C0141, C0140,
		C0139, C0138, C0137, C0136, C0135, C0134, C0133, C0132, C0131, C0130,
		C0129, C0128, C0127, C0126, C0125, C0124, C0123, C0122, C0121, C0120,
		C0119, C0118, C0117, C0116, C0115, C0114, C0113, C0112, C0111, C0110,
		C0109, C0108, C0107, C0106, C0105, C0104, C0103, C0102, C0101, C0100,
		C0099, C0098, C0097, C0096, C0095, C0094, C0093, C0092, C0091, C0090,
		C0089, C0088, C0087, C0086, C0085, C0084, C0083, C0082, C0081, C0080,
		C0079, C0078, C0077, C0076, C0075, C0074, C0073, C0072, C0071, C0070,
		C0069, C0068, C0067, C0066, C0065, C0064, C0063, C0062, C0061, C0060,
		C0059, C0058, C0057, C0056, C0055, C0054, C0053, C0052, C0051, C0050,
		C0049, C0048, C0047, C0046, C0045, C0044, C0043, C0042, C0041, C0040,
		C0039, C0038, C0037, C0036, C0035, C0034, C0033, C0032, C0031, C0030,
		C0029, C0028, C0027, C0026, C0025, C0024, C0023, C0022, C0021, C0020,
		C0019, C0018, C0017, C0016, C0015, C0014, C0013, C0012, C0011, C0010,
		C0009, C0008, C0007, C0006, C0005, C0004, C0003, C0002, C0001, C0000
	}

	/** The size of {@link Thousand}, so a test never re-counts what the generator already knows. */
	public static final int CONSTANT_COUNT = 1000;

	/**
	 * A record of {@value #COMPONENT_COUNT} components, declared in <b>descending</b> name order and cycling
	 * through four different scalar rows, so both the emitted {@code properties} order and the emitted
	 * {@code required} order are the canonical-constructor order rather than anything sorted.
	 *
	 * <p>Every component occupies one JVM local slot ({@code long}/{@code double} are deliberately absent):
	 * the canonical constructor's 255-slot limit is what caps this fixture, not the mapping.
	 */
	public record Wide(
		Character c199, boolean c198, int c197, String c196,
		Character c195, boolean c194, int c193, String c192,
		Character c191, boolean c190, int c189, String c188,
		Character c187, boolean c186, int c185, String c184,
		Character c183, boolean c182, int c181, String c180,
		Character c179, boolean c178, int c177, String c176,
		Character c175, boolean c174, int c173, String c172,
		Character c171, boolean c170, int c169, String c168,
		Character c167, boolean c166, int c165, String c164,
		Character c163, boolean c162, int c161, String c160,
		Character c159, boolean c158, int c157, String c156,
		Character c155, boolean c154, int c153, String c152,
		Character c151, boolean c150, int c149, String c148,
		Character c147, boolean c146, int c145, String c144,
		Character c143, boolean c142, int c141, String c140,
		Character c139, boolean c138, int c137, String c136,
		Character c135, boolean c134, int c133, String c132,
		Character c131, boolean c130, int c129, String c128,
		Character c127, boolean c126, int c125, String c124,
		Character c123, boolean c122, int c121, String c120,
		Character c119, boolean c118, int c117, String c116,
		Character c115, boolean c114, int c113, String c112,
		Character c111, boolean c110, int c109, String c108,
		Character c107, boolean c106, int c105, String c104,
		Character c103, boolean c102, int c101, String c100,
		Character c099, boolean c098, int c097, String c096,
		Character c095, boolean c094, int c093, String c092,
		Character c091, boolean c090, int c089, String c088,
		Character c087, boolean c086, int c085, String c084,
		Character c083, boolean c082, int c081, String c080,
		Character c079, boolean c078, int c077, String c076,
		Character c075, boolean c074, int c073, String c072,
		Character c071, boolean c070, int c069, String c068,
		Character c067, boolean c066, int c065, String c064,
		Character c063, boolean c062, int c061, String c060,
		Character c059, boolean c058, int c057, String c056,
		Character c055, boolean c054, int c053, String c052,
		Character c051, boolean c050, int c049, String c048,
		Character c047, boolean c046, int c045, String c044,
		Character c043, boolean c042, int c041, String c040,
		Character c039, boolean c038, int c037, String c036,
		Character c035, boolean c034, int c033, String c032,
		Character c031, boolean c030, int c029, String c028,
		Character c027, boolean c026, int c025, String c024,
		Character c023, boolean c022, int c021, String c020,
		Character c019, boolean c018, int c017, String c016,
		Character c015, boolean c014, int c013, String c012,
		Character c011, boolean c010, int c009, String c008,
		Character c007, boolean c006, int c005, String c004,
		Character c003, boolean c002, int c001, String c000
	) {}

	/** The number of components of {@link Wide}. */
	public static final int COMPONENT_COUNT = 200;

	/**
	 * A chain of {@value #CHAIN_DEPTH} <b>distinct</b> record classes, {@code L00} … {@code L49}, each one
	 * holding the previous. Distinct on purpose: the cycle guard is keyed by raw {@code Class}, so a chain of
	 * one generic record nested in itself would be refused as a cycle (see
	 * {@code JsonSchemaMappingAdversarialTest.aGenericRecordNestedInItself…}). This chain is not a cycle by
	 * any reading, and must resolve in full.
	 */
	public static final int CHAIN_DEPTH = 50;

	public record L00(String leaf) {}
	public record L01(L00 next) {}
	public record L02(L01 next) {}
	public record L03(L02 next) {}
	public record L04(L03 next) {}
	public record L05(L04 next) {}
	public record L06(L05 next) {}
	public record L07(L06 next) {}
	public record L08(L07 next) {}
	public record L09(L08 next) {}
	public record L10(L09 next) {}
	public record L11(L10 next) {}
	public record L12(L11 next) {}
	public record L13(L12 next) {}
	public record L14(L13 next) {}
	public record L15(L14 next) {}
	public record L16(L15 next) {}
	public record L17(L16 next) {}
	public record L18(L17 next) {}
	public record L19(L18 next) {}
	public record L20(L19 next) {}
	public record L21(L20 next) {}
	public record L22(L21 next) {}
	public record L23(L22 next) {}
	public record L24(L23 next) {}
	public record L25(L24 next) {}
	public record L26(L25 next) {}
	public record L27(L26 next) {}
	public record L28(L27 next) {}
	public record L29(L28 next) {}
	public record L30(L29 next) {}
	public record L31(L30 next) {}
	public record L32(L31 next) {}
	public record L33(L32 next) {}
	public record L34(L33 next) {}
	public record L35(L34 next) {}
	public record L36(L35 next) {}
	public record L37(L36 next) {}
	public record L38(L37 next) {}
	public record L39(L38 next) {}
	public record L40(L39 next) {}
	public record L41(L40 next) {}
	public record L42(L41 next) {}
	public record L43(L42 next) {}
	public record L44(L43 next) {}
	public record L45(L44 next) {}
	public record L46(L45 next) {}
	public record L47(L46 next) {}
	public record L48(L47 next) {}
	public record L49(L48 next) {}
}
