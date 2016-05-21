import scala.collection.mutable
import org.apache.spark.mllib.clustering.{EMLDAOptimizer, OnlineLDAOptimizer, DistributedLDAModel, LDA}
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

object GroupCluster {
  def main(args: Array[String]) {
	val inDataPath = "/data/wordseg/rt"
	val outTopicWordsPath = "/data/GroupCluster/outTopicWords"
	val outGidMaxTopic	  = "/data/GroupCluster/outGidMaxTopic"
	// Set LDA parameters
	val numStopwords = 20   //出现频率最高的20词为停用词
	val maxIteration = 10 //迭代次数
	val numTopics = 10 //主题数
	val maxTermsPerTopic = 10 //对每个主题概率最大的10个词

	val conf = new SparkConf().setAppName("GroupCluster")
	val sc = new SparkContext(conf)
	val text = sc.textFile(inDataPath)
	//(群号->关键字数组)
	val tokenized: RDD[(Long,Seq[String])] = text.map{line => val t = line.split(":")
	(t(0).toLong, t(1).split("\\s"))
	}
	//去掉词频最高的numStopwords个词
	val termCounts: Array[(String, Long)] =
	  tokenized.flatMap(_._2.map(_ -> 1L)).reduceByKey(_ + _).collect().sortBy(-_._2)
	val vocabArray: Array[String] =  termCounts.takeRight(termCounts.size - numStopwords).map(_._1) 
	//val vocabArray: Array[String] = tokenized.flatMap(_._2).distinct.collect

	//每个词打下标 vocab: Map term -> term index
	val vocab: Map[String, Int] = vocabArray.zipWithIndex.toMap
	//(群号,(总词数,[词1id,词2id ...],[词1的词频, ...]))
	val documents: RDD[(Long, Vector)] = tokenized.map{case(gid, tokens)=>
	    val counts = new mutable.HashMap[Int, Double]()
	    tokens.foreach { term =>
	      if (vocab.contains(term)) {
		val idx = vocab(term)
		counts(idx) = counts.getOrElse(idx, 0.0) + 1.0
	      }
	    }
	    (gid,Vectors.sparse(vocab.size, counts.toSeq))
		}

	val lda = new LDA().setK(numTopics).setMaxIterations(maxIteration)
	val ldaModel = lda.run(documents)
	val distLDAModel = ldaModel.asInstanceOf[DistributedLDAModel]
	//文档到主题的概率集合
	val docTopicProb =  distLDAModel.topicDistributions
	//(gid,最大概率的主题id,概率)
	val gidTopic:RDD[(Long,Int,Double)] = docTopicProb.map{case(gid,vec) => 
	var maxTopic = 0
	var max = 0.0
	for(i <- 0 to (vec.size - 1)) {
		var t = vec.apply(i)
		if(max < t){
				max = t
				maxTopic = i
			}
		}
		(gid, maxTopic, max)
	}

	gidTopic.saveAsTextFile(outGidMaxTopic)
	//每个主题top maxTermsPerTopic词
	val topicIndices = distLDAModel.describeTopics(maxTermsPerTopic)
	val topicWords = topicIndices.zipWithIndex.map{case((terms,termWeights),topicId) =>
		var words = Array[String]()
		terms.foreach{term => words=words:+vocabArray(term)}
		(topicId,words,termWeights)
		}

	val topicWordsRdd = sc.parallelize(topicWords, numTopics).map{case(topicId,words,termWeights) => (topicId,words.mkString("\t"),termWeights.mkString("\t"))}
	topicWordsRdd.saveAsTextFile(outTopicWordsPath)

	topicIndices.foreach { case (terms, termWeights) =>
	  println("TOPIC:")
	  terms.zip(termWeights).foreach { case (term, weight) =>
	    println(s"${vocabArray(term.toInt)}\t$weight")
	  }
	  println()
	}

	}
}
