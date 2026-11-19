import binascii, string, re, sys


try:
    from Crypto.Cipher import DES3
    from Crypto.Cipher import DES
    from Crypto.Util.strxor import strxor
    from Crypto.Hash import SHA
except ImportError:
    print("Package pycryptodome not found.")
    import sys
    sys.exit()


class hexa_string:
    regexp = re.compile("[0-9a-fA-F]{2}")

    def __init__(self, s):
        for i in string.whitespace:
            s = str(s).replace(i, "")
        if s.startswith("b'"):
            s = s[2:-1]
        if "b'" in s:
            assert False
        length = int(len(s) / 2 * 2)
        self.string = s.lower()[:length]

    def getBinary(self):
        s = self.string.replace(" ", "")
        assert isinstance(s, str)
        return binascii.a2b_hex(s)

    def toBinary(self):
        s = self.string.replace(" ", "")
        assert isinstance(s, str)
        return binascii.a2b_hex(s)

    @staticmethod
    def fromBinary(s):
        assert isinstance(s, bytes)
        return hexa_string(binascii.b2a_hex(s))


    @staticmethod
    def fromNumber(i, width=0):
        s = hex(i)
        # Remove final "L"
        if s[-1] == "L":
            s = s[:-1]

        # Remove initial "0x"
        s = s[2:]

        # Add 0 if odd length
        if len(s) % 2 == 1:
            s = '0' + s

        if width == 0:
            return hexa_string(s)
        else:
            return hexa_string(s.rjust(int(2 * width), "0"))

    def toNumber(self):
        return int(self.string, 16)

    def __repr__(self):
        return " ".join(self.regexp.findall(self.string))

    def __str__(self):
        return self.__repr__()

    def __getitem__(self, index):
        tmp = binascii.a2b_hex(self.string)
        fragment = tmp[index]
        return hexa_string(binascii.b2a_hex(fragment))

    def __call__(self, *args):
        start = args[0]
        end = args[1]
        return self[start:start + end]

    def __len__(self):
        return len(self.string) / 2

    def __add__(self, string):
        return hexa_string(self.string + str(string))

    def __radd__(self, string):
        return hexa_string(str(string) + self.string)

    def __mul__(self, int):
        return hexa_string(self.string * int)

    def __cmp__(self, string):
        v = self.toNumber() - string.toNumber()
        if v < 0:
            return -1
        elif v > 0:
            return 1
        else:
            return 0

    def __eq__(self, string):
        return self.string.upper() == hexa_string(string).string.upper()

    def __ne__(self, string):
        return self.string.upper() != hexa_string(string).string.upper()

    def __hash__(self):
        return hash(self.string)





def Encrypt_DES3_ECB(key, message):
    key_Binary = hexa_string(key).getBinary()
    messageB = hexa_string(message).getBinary()

    algo = None
    if key_Binary[:8] == key_Binary[8:16]:
        algo = DES.new(key_Binary[:8], DES3.MODE_ECB)
    else:
        algo = DES3.new(key_Binary, DES3.MODE_ECB)
    return hexa_string.fromBinary(algo.encrypt(messageB))




def Decrypt_DES3_ECB(key, message, icv="0000000000000000"):

    if icv != "0000000000000000":
        print("Crypto error: unexpected icv value")
        sys.exit()

    key_Binary = hexa_string(key).getBinary()
    message_Binary = hexa_string(message).getBinary()

    algo = None
    if key_Binary[:8] == key_Binary[8:16]:
        algo = DES.new(key_Binary[:8], DES.MODE_ECB)
    else:
        algo = DES3.new(key_Binary, DES3.MODE_ECB)
    return hexa_string.fromBinary(algo.decrypt(message_Binary))


def Encrypt_DES3_CBC(key, message, icv="0000000000000000"):
    key_Binary = hexa_string(key).getBinary()
    message_Binary = hexa_string(message).getBinary()
    icv_Binary = hexa_string(icv).getBinary()
    algo = None

    if key_Binary[:8] == key_Binary[8:16]:
        algo = DES.new(key_Binary[:8], DES.MODE_CBC, icv_Binary)
    else:
        algo = DES3.new(key_Binary, DES3.MODE_CBC, icv_Binary)
    return hexa_string.fromBinary(algo.encrypt(message_Binary))





def xor(block1, block2):
    block1 = hexa_string(block1).getBinary()
    block2 = hexa_string(block2).getBinary()
    xored = strxor(block1, block2)
    return hexa_string.fromBinary(xored)
    


def rsa(keyExp, keyMod, msg):
    keyExpL = int(hexa_string(keyExp).string, 16)
    keyModL = int(hexa_string(keyMod).string, 16)
    msgL = int(hexa_string(msg).string, 16)
    result = pow(msgL, keyExpL, keyModL)
    return hexa_string.fromNumber(result, int(len(hexa_string(keyMod).string) / 2))


def MAC_DES(key, message, icv="0000000000000000"):
    k1 = hexa_string(key)[:8]
    k2 = hexa_string(key)[8:]
    tmpResult = MAC_DES3(k1 + k1, message, icv)
    tmpResult = Decrypt_DES3_ECB(k2 + k2, tmpResult, "0000000000000000")
    return Encrypt_DES3_ECB(k1 + k1, tmpResult)



def MAC_DES3(key, message, icv="0000000000000000"):
    des = Encrypt_DES3_CBC(key, message, icv)
    return des[-8:]



def DeriveUDK(IMK, PAN, PSN, adjust_parity = False, verbose=False):
    UDK = ""
    PAN = PAN.replace('F', '')

    while len(PSN) < 2: PSN = "0" + PSN
    PSN = PSN[:2]
    
    # built input buffer
    msg = PAN + PSN
    while len(msg) < 16: msg = "0" + msg
    msg = msg[-16:]
    
    if verbose==True:
        print()
        print("UDK derivation:")
        print("  input buffer:", msg, "from PAN '%s' and PSN '%s'" % (PAN, PSN))
        print("  key:", IMK)

    
    UDK_L = MAC_DES3(IMK, msg).string.upper()
    msg = xor(msg, "FFFFFFFFFFFFFFFF").string.upper()
    UDK_R = MAC_DES3(IMK, msg).string.upper()

    UDK = UDK_L + UDK_R

    if verbose==True:
        print("  UDK_L:", UDK_L)
        print("  modified input buffer:", msg)
        print("  UDK_R:", UDK_R)
        print()

    
    if adjust_parity == True:
        UDK = adjustOddParity(UDK) 
    
    return UDK





# returns -1 is odd, 0 if even
def parityOf(int_type):

    parity = 0
    while (int_type):
        parity = ~parity
        int_type = int_type & (int_type - 1)
    return(parity)
    


def adjustOddParity(k):
    
    k = k.replace(" ", "").replace(".", "")
    adj_key = ""
    
    for i in range(int(len(k)/2)):
        B = int(k[2*i:2*i+2], 16)
        
        if parityOf(B) == 0:
            
            if B & 0x01 == 0x01:
                B = B - 1
            else:
                B = B + 1
        
        
        adj_key = adj_key + "%02X" % B
        
    return adj_key
        
        


def ComputeARQC_CVN10(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""
    
    CVR = IAD[6:14]
    msg = CDOL1[:58] + AIP + ATC + CVR
    while len(msg) % 16 != 0: msg = msg + "0"
    
    ARQC =  MAC_DES(UDK_AC, msg).string.upper()
    
    if verbose==True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and UDK_AC")
        print()
    
    return ARQC


def ComputeARQC_CVN17(TerminalData, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""
    
    IAD_Byte5 = IAD[8:10]
    msg = TerminalData + ATC + IAD_Byte5
    while len(msg) % 16 != 0: msg = msg + "0"
    
    ARQC =  MAC_DES(UDK_AC, msg).string.upper()
    
    if verbose==True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and UDK_AC")
        print()
    
    return ARQC


def ComputeARQC_CVN18(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=False):
    ARQC, SDK = ComputeARQC_DPAS(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=verbose)
    
    return ARQC, SDK

def ComputeARQC_MCHIP4(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""
    
    UN = CDOL1[50:58]
    # div_A = ATC + "F00000000000"
    # div_B = ATC + "0F0000000000"

    div_A = ATC + "F000" + UN
    div_B = ATC + "0F00" + UN

    
    SDK_A = MAC_DES(UDK_AC, div_A).string.upper()
    SDK_B = MAC_DES(UDK_AC, div_B).string.upper()
    
    SDK = SDK_A + SDK_B

    CVR = IAD[4:16]
    Counters = ""
    CVN_Version = int(IAD[2:4],16)
    if (CVN_Version & 0x01) == 0x01:
        Counters = IAD[20:]

    msg = CDOL1[:58] + AIP + ATC + CVR + Counters + "80"
    while len(msg) % 16 != 0: msg = msg + "0"

    ARQC =  MAC_DES(SDK, msg).string.upper()
    
    if verbose==True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  deriving session key:")
        print("    div_A:", div_A, "gives SDK_A", SDK_A)
        print("    div_B:", div_B, "gives SDK_B", SDK_B)
        print("    SDK:", SDK)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and SDK")
        print()
    
    return ARQC


def ComputeARQC_DPAS(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""
    
    div_A = ATC + "F00000000000"
    div_B = ATC + "0F0000000000"
    
    SDK_A = MAC_DES(UDK_AC, div_A).string.upper()
    SDK_B = MAC_DES(UDK_AC, div_B).string.upper()
    
    SDK = SDK_A + SDK_B
    
    msg = CDOL1[:58] + AIP + ATC + IAD + "80"
    if IAD[2:4] == "06": msg = CDOL1[:58] + AIP + ATC + IAD[4:16] + "80"
        
    while len(msg) % 16 != 0: msg = msg + "0"
    ARQC =  MAC_DES(SDK, msg).string.upper()
    
    if verbose==True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  deriving session key:")
        print("    div_A:", div_A, "gives SDK_A", SDK_A)
        print("    div_B:", div_B, "gives SDK_B", SDK_B)
        print("    SDK:", SDK)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and SDK")
        print()
    
    
    return ARQC, SDK


def ComputeARQC_DPAS_CL(AmountAuthorized, TransactionCurrencyCode, UN, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""
    
    div_A = ATC + "F00000000000"
    div_B = ATC + "0F0000000000"
    
    SDK_A = MAC_DES(UDK_AC, div_A).string.upper()
    SDK_B = MAC_DES(UDK_AC, div_B).string.upper()
    
    SDK = SDK_A + SDK_B
    
    msg = AmountAuthorized + TransactionCurrencyCode + UN + ATC + IAD + "80"
    if IAD[2:4] == "16": msg = AmountAuthorized + TransactionCurrencyCode + UN + ATC + IAD[4:18] + "80"
    while len(msg) % 16 != 0: msg = msg + "0"
    ARQC =  MAC_DES(SDK, msg).string.upper()
    
    if verbose==True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  deriving session key:")
        print("    div_A:", div_A, "gives SDK_A", SDK_A)
        print("    div_B:", div_B, "gives SDK_B", SDK_B)
        print("    SDK:", SDK)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and SDK")
        print()
    
    return ARQC, SDK


def ComputeARQC_UPI(CDOL1, AIP, ATC, IAD, UDK_AC):
    ARQC = ""
    
    div_A = "000000000000" + ATC
    div_B = "000000000000" + xor(ATC, "FFFF").string.replace(" ", "").upper()
    
    SDK_A = MAC_DES(UDK_AC, div_A).string.upper()
    SDK_B = MAC_DES(UDK_AC, div_B).string.upper()
    
    SDK = SDK_A + SDK_B
    
    msg = CDOL1[:58] + AIP + ATC + IAD[6:14] + "80"

    while len(msg) % 16 != 0: msg = msg + "0"
    ARQC =  MAC_DES(SDK, msg).string.upper()
    
    return ARQC, SDK


def ComputeARQC_Amex_CVN01(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""
   
    CVR = IAD[6:]
    msg = CDOL1[:58] + AIP + ATC + CVR
        
    while len(msg) % 16 != 0: msg = msg + "0"
    ARQC =  MAC_DES(UDK_AC, msg).string.upper()
    
    if verbose==True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and UDK_AC")
        print()
    
    return ARQC


def ComputeARQC_Amex_CVN02(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""

    CVR = IAD[6:]
    msg = CDOL1 + ATC + CVR

    while len(msg) % 16 != 0: msg = msg + "0"
    ARQC = MAC_DES(UDK_AC, msg).string.upper()

    if verbose == True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and UDK_AC")
        print()

    return ARQC


def ComputeARQC_CVN85(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""

    R = CDOL1[50:58]

    div_A = ATC + "F000" + R
    div_B = ATC + "0F00" + R


    SDK_A = MAC_DES(UDK_AC, div_A).string.upper()
    SDK_B = MAC_DES(UDK_AC, div_B).string.upper()

    SDK = SDK_A + SDK_B


    msg = CDOL1[:58] + AIP + ATC + IAD + "80"


    while len(msg) % 16 != 0: msg = msg + "0"
    ARQC = MAC_DES(SDK, msg).string.upper()

    if verbose == True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  deriving session key:")
        print("    div_A:", div_A, "gives SDK_A", SDK_A)
        print("    div_B:", div_B, "gives SDK_B", SDK_B)
        print("    SDK:", SDK)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and SDK")
        print()

    return ARQC, SDK


def ComputeARQC_JCB_CVN1(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""

    verbose = True

    CVR = IAD[6:16]
    msg = CDOL1[:58] + AIP + ATC + CVR
    while len(msg) % 16 != 0: msg = msg + "0"

    ARQC = MAC_DES(UDK_AC, msg).string.upper()

    if verbose == True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and UDK_AC")
        print()

    return ARQC

def ComputeARQC_JCB_CVN2(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""

    verbose = True

    div_A = "000000000000" + ATC
    UDK_A = UDK_AC[:16]
    SDK_A = xor(div_A, UDK_A).string.upper()

    div_B = "000000000000" + xor(ATC, "FFFF").string.upper()
    UDK_B = UDK_AC[16:]
    SDK_B = xor(div_B, UDK_B).string.upper()

    SDK = SDK_A + SDK_B

    CVR = IAD[6:16]
    msg = CDOL1[:58] + AIP + ATC + CVR
    print(msg)
    while len(msg) % 16 != 0: msg = msg + "0"

    ARQC = MAC_DES(SDK, msg).string.upper()

    if verbose == True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  deriving session key:")
        print("    div_A:", div_A, "gives SDK_A", SDK_A)
        print("    div_B:", div_B, "gives SDK_B", SDK_B)
        print("    SDK:", SDK)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and UDK_AC")
        print()

    return ARQC

def ComputeARQC_JCB_CVN4(CDOL1, AIP, ATC, IAD, UDK_AC, verbose=False):
    ARQC = ""

    div_A = ATC + "F00000000000"
    div_B = ATC + "0F0000000000"

    SDK_A = MAC_DES(UDK_AC, div_A).string.upper()
    SDK_B = MAC_DES(UDK_AC, div_B).string.upper()

    SDK = SDK_A + SDK_B
    msg = CDOL1[:58] + AIP + ATC + IAD[6:] + "80"


    while len(msg) % 16 != 0: msg = msg + "0"
    ARQC = MAC_DES(SDK, msg).string.upper()

    if verbose == True:
        print()
        print("ARQC computation:")
        print("  UDK_AC:", UDK_AC)
        print("  deriving session key:")
        print("    div_A:", div_A, "gives SDK_A", SDK_A)
        print("    div_B:", div_B, "gives SDK_B", SDK_B)
        print("    SDK:", SDK)
        print("  input buffer:", msg)
        print("  computed ARQC:", ARQC, "from input buffer and SDK")
        print()

    return ARQC, SDK

def ComputeARPC(ARQC, ARC, UDK_AC, verbose=False):
    ARPC = ""

    while len(ARC) < 16: ARC = ARC + "0"
    msg = xor(ARC, ARQC).string.upper()
    ARPC =  MAC_DES(UDK_AC, msg).string.upper()
    
    if verbose==True:
        print()
        print("ARPC computing:")
        print("  ARQC from transaction:", ARQC)
        print("  ARPC Response Code (padded):", ARC)
        print("  input buffer:", msg)
        print("  UDK_AC:", UDK_AC)
        print("  computed ARPC:", ARPC)
        print()
    
    return ARPC    


def ComputeARPC_CVN18(ARQC, CSU, SDK_AC, verbose=False):
    ARPC = ""
    
    Y = ARQC + CSU + "80"
    while len(Y) < 32: Y = Y + "0"
    ARPC = MAC_DES(SDK_AC, Y).string.upper()[:8]
    
    if verbose==True:
        print()
        print("ARPC computing:")
        print("  ARQC from transaction:", ARQC)
        print("  CSU:", CSU)
        print("  input buffer:", Y)
        print("  SDK_AC:", SDK_AC)
        print("  computed ARPC:", ARPC)
        print()
    
    return ARPC


def ComputeARPC_CVN4(ARQC, CSU, SDK_AC, verbose=False):
    ARPC = ""

    Y = ARQC + CSU + "000000000000000080"
    while len(Y) < 48: Y = Y + "0"
    ARPC = MAC_DES(SDK_AC, Y).string.upper()[:8]

    if verbose == True:
        print()
        print("ARPC computing:")
        print("  ARQC from transaction:", ARQC)
        print("  CSU:", CSU)
        print("  input buffer:", Y)
        print("  SDK_AC:", SDK_AC)
        print("  computed ARPC:", ARPC)
        print()

    return ARPC


def ComputeMAC(CMD, ARQC, ATC, UDK_SMI, bMC_SKD = False, bUPI_SDK = False, bInterac_MAC = False, nMAC_length = 8, verbose=False, CMD_Data=""):
    MAC = ""
    
    SDK_A = ""
    SDK_B = ""


    if verbose==True:
        print()
        print("MAC computation:")
        print("  ARQC from transaction:", ARQC)
        print("  ATC:", ATC)
        print("  UDK_MAC/SMI:", UDK_SMI)

    if bMC_SKD == True:
        RND = ARQC    
 
        RND_A = RND[:4] + "F0" + RND[6:]
        RND_B = RND[:4] + "0F" + RND[6:]

        if verbose == True:
            print("  Div A:", RND_A, "Div B:", RND_B)
        
        SDK_A = MAC_DES(UDK_SMI, RND_A).string.upper()
        SDK_B = MAC_DES(UDK_SMI, RND_B).string.upper()
        
    elif bUPI_SDK == True: 
        div_A = "000000000000" + ATC
        div_B = "000000000000" + xor(ATC, "FFFF").string.replace(" ", "").upper()

        if verbose == True:
            print("  Div A:", div_A, "Div B:", div_B)
        
        SDK_A = MAC_DES(UDK_SMI, div_A).string.upper()
        SDK_B = MAC_DES(UDK_SMI, div_B).string.upper()
    
    else:
        div_A = "000000000000" + ATC
        div_B = "000000000000%04X" % (0xFFFF - int(hexa_string(ATC).string, 16))

        if verbose == True:
            print("  Div A:", div_A, "Div B:", div_B)
        
        SDK_A = xor(div_A, UDK_SMI[:16]).string.upper()
        SDK_B = xor(div_B, UDK_SMI[16:]).string.upper()


    
    SDK = SDK_A + SDK_B
    
    msg = CMD + ATC + ARQC + CMD_Data + "80"

    
    if len(CMD) > 10:
        msg = CMD[:10] + ATC + ARQC + CMD[10:] + "80"

    if bInterac_MAC == True:
        msg = CMD + "80"
    
    while len(msg) % 16 != 0: msg = msg + "0"
    
    MAC = MAC_DES(SDK, msg).string.upper()[:2 * nMAC_length]

    if verbose==True:
        print("  SDK_MAC/SMI:", SDK, "  Parity correction:", adjustOddParity(SDK))
        print("  input buffer:", msg)
        print("  computed MAC:", MAC)
        print()
    
    return MAC


def ComputeDEA(plaintext, ATC, UDK_SMC, ARQC, bMC_SKD = False, verbose=False):
    MAC = ""

    SDK_A = ""
    SDK_B = ""

    if verbose == True:
        print()
        print("DEA computation:")
        print("  ATC:", ATC)
        print("  UDK_DEA/SMC:", UDK_SMC)

    # TODO: add cases for other brands
    SDK = ""
    if bMC_SKD == True:
        RND = ARQC

        RND_A = RND[:4] + "F0" + RND[6:]
        RND_B = RND[:4] + "0F" + RND[6:]

        if verbose == True:
            print("  ARQC:", ARQC)
            print("  Div A:", RND_A, "Div B:", RND_B)

        SDK_A = MAC_DES(UDK_SMC, RND_A).string.upper()
        SDK_B = MAC_DES(UDK_SMC, RND_B).string.upper()

        SDK = SDK_A + SDK_B

    else:
        div_A = "000000000000" + ATC
        div_B = "000000000000%04X" % (0xFFFF - int(hexa_string(ATC).string, 16))

        if verbose == True:
            print("  Div A:", div_A, "Div B:", div_B)

        SDK_A = xor(div_A, UDK_SMC[:16]).string.upper()
        SDK_B = xor(div_B, UDK_SMC[16:]).string.upper()

        SDK = SDK_A + SDK_B

    encrypted = Encrypt_DES3_ECB(SDK, plaintext).string.upper()

    if verbose == True:
        print("  SDK_DEA/SMC:", SDK, "  Parity correction:", adjustOddParity(SDK))
        print("  input buffer:", plaintext)
        print("  computed DEA:", encrypted)
        print()

    return encrypted





try:
    import hashlib

    def sha1(plainData):
        binData = hexa_string(plainData).getBinary()
        hash = hashlib.sha1(binData).hexdigest()
        return hexa_string(hash)

    def sha224(plainData):
        binData = hexa_string(plainData).getBinary()
        hash = hashlib.sha224(binData).hexdigest()
        return hexa_string(hash)

    def sha256(plainData):
        binData = hexa_string(plainData).getBinary()
        hash = hashlib.sha256(binData).hexdigest()
        return hexa_string(hash)



    

except ImportError:
    print("Package hashlib not found.")

    def sha1(plainData):
        newData = hexa_string(plainData)
        algo = SHA.new()
        algo.update(newData.getBinary())
        result  = algo.digest()
        return  hexa_string.fromBinary(result)


